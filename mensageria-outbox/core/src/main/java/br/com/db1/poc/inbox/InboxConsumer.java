package br.com.db1.poc.inbox;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.shared.logging.MessageMdc;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumidor da inbox: recebe as mensagens da fila principal pela {@link MessageSource}, aplica cada uma em uma
 * transação local junto do registro do {@code messageId} na inbox e só então a confirma ao broker.
 *
 * <ul>
 *   <li>Uma mensagem por {@code aggregateKey} por vez, na ordem de entrega; chaves diferentes em paralelo. Uma
 *       mensagem sem {@code aggregateKey} legível é serializada sob a chave reservada {@value #RESERVED_KEY}.
 *   <li>Falha de processamento: a transação é desfeita e a mensagem é repetida na memória depois de
 *       {@code min(backoff.max, backoff.initial × backoff.multiplier^(n-1))}, com a chave em espera. Ao exceder
 *       {@code max-attempts}, a mensagem vai para a DLQ e a chave fica bloqueada: as mensagens seguintes dela
 *       também vão para a DLQ, sem passar pelo tratador.
 *   <li>Publicação na DLQ que falha: a original permanece sem ack e a publicação é repetida pelos intervalos do
 *       backoff, sem contar como tentativa de processamento.
 *   <li>Ack que falha depois do commit é registrado em log: o broker reentrega a mensagem e a inbox a descarta.
 *   <li>As mensagens em voo são limitadas por {@code max-in-flight}, o {@code basicQos} da porta de consumo; as que
 *       aguardam a vez da chave ou o backoff contam nesse limite.
 * </ul>
 *
 * <p>Os logs de cada tentativa trazem no MDC {@code messageId}, {@code correlationId}, {@code aggregateKey} e
 * {@code traceId} dos headers que a mensagem tem.
 */
public final class InboxConsumer implements AutoCloseable {

    /** Chave de serialização das mensagens sem {@code aggregateKey} legível. */
    public static final String RESERVED_KEY = "<sem-aggregateKey>";

    static final String FAILURE_MAX_ATTEMPTS = "max-attempts";
    static final String FAILURE_BLOCKED_KEY = "blocked-key";

    private static final long SHUTDOWN_WAIT_MILLIS = 5_000L;
    private static final Logger LOG = LoggerFactory.getLogger(InboxConsumer.class);

    /** Estado de uma mensagem em voo: a entrega, a chave, a vez da chave e os contadores de falha. */
    private static final class InFlight {

        final Delivery delivery;
        final String key;
        KeyedExecutor.Turn turn;
        int attempts;
        int deadLetterFailures;

        InFlight(Delivery delivery, String key) {
            this.delivery = delivery;
            this.key = key;
        }
    }

    private final InboxConfig config;
    private final MessageSource source;
    private final InboxProcessor processor;
    private final BlockedKeys blockedKeys;
    private final InboxMetrics metrics;
    private final RetryBackoff backoff;
    private final KeyedExecutor executor;
    private final RetryScheduler scheduler;

    private boolean started;
    private boolean closed;

    public InboxConsumer(InboxConfig config, MessageSource source, InboxDialect dialect,
            InboxTransactionExecutor transactions, InboxMessageHandler handler, BlockedKeys blockedKeys,
            InboxMetrics metrics) {
        this(config, source, dialect, transactions, handler, blockedKeys, metrics, RetryScheduler.threaded());
    }

    InboxConsumer(InboxConfig config, MessageSource source, InboxDialect dialect,
            InboxTransactionExecutor transactions, InboxMessageHandler handler, BlockedKeys blockedKeys,
            InboxMetrics metrics, RetryScheduler scheduler) {
        this.config = config;
        this.source = source;
        this.processor = new InboxProcessor(dialect, transactions, handler);
        this.blockedKeys = blockedKeys;
        this.metrics = metrics;
        this.backoff = new RetryBackoff(config.backoffInitial(), config.backoffMultiplier(), config.backoffMax());
        this.executor = new KeyedExecutor(config.workers());
        this.scheduler = scheduler;
    }

    /**
     * Confirma as duas filas, registra o consumidor e começa a receber mensagens.
     *
     * @throws IllegalStateException o consumidor já foi iniciado ou fechado, ou o broker está inacessível ou não tem
     *                               uma das filas
     */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("O consumidor foi fechado");
        }
        if (started) {
            throw new IllegalStateException("O consumidor já foi iniciado");
        }
        try {
            source.start(config.queue(), config.deadLetterQueue(), config.maxInFlight(), this::onDelivery);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Falha ao iniciar o consumo da fila '" + config.queue() + "': " + e.getMessage(), e);
        }
        started = true;
    }

    /**
     * Cancela o consumo, deixa terminar as tentativas em andamento e fecha a porta de consumo. As mensagens que
     * não terminaram ficam sem ack e o broker as reentrega; a inbox descarta as que já foram confirmadas.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        scheduler.close();
        executor.shutdown(SHUTDOWN_WAIT_MILLIS);
        source.close();
    }

    /** Chaves de agregado com mensagem em processamento ou aguardando; zero quando o consumidor está ocioso. */
    int activeKeys() {
        return executor.activeKeys();
    }

    private void onDelivery(Delivery delivery) {
        InFlight message = new InFlight(delivery, keyOf(delivery));
        try {
            executor.submit(message.key, turn -> {
                message.turn = turn;
                inContext(message, () -> attempt(message));
            });
        } catch (RejectedExecutionException e) {
            requeue(delivery);
        }
    }

    private void attempt(InFlight message) {
        if (blockedKeys.isBlocked(message.key)) {
            LOG.warn("Chave '{}' bloqueada: a mensagem vai para a DLQ sem ser processada", message.key);
            deadLetter(message, FAILURE_BLOCKED_KEY, 0);
            return;
        }
        message.attempts++;
        InboxProcessor.Result result;
        try {
            result = processor.process(message.delivery);
        } catch (Exception e) {
            failed(message, e);
            return;
        }
        if (result == InboxProcessor.Result.DUPLICATE) {
            metrics.recordDuplicate();
            LOG.info("Mensagem já consumida: descartada");
        }
        acknowledge(message);
    }

    private void acknowledge(InFlight message) {
        try {
            source.ack(message.delivery);
        } catch (IOException e) {
            LOG.warn("Ack não enviado depois do commit; o broker reentregará a mensagem e a inbox a descartará: {}",
                    e.toString());
        } finally {
            message.turn.release();
        }
    }

    private void failed(InFlight message, Exception failure) {
        int attempts = message.attempts;
        if (attempts < config.maxAttempts()) {
            long delay = backoff.delayMillis(attempts);
            metrics.recordRetry();
            LOG.warn("Falha na tentativa {} de {}; nova tentativa em {} ms", attempts, config.maxAttempts(), delay,
                    failure);
            later(message, delay, () -> attempt(message));
            return;
        }
        LOG.error("Falha na tentativa {} de {}; a mensagem vai para a DLQ", attempts, config.maxAttempts(), failure);
        deadLetter(message, FAILURE_MAX_ATTEMPTS, attempts);
    }

    private void deadLetter(InFlight message, String failure, int attempts) {
        try {
            source.deadLetter(message.delivery, failure, attempts);
        } catch (StaleDeliveryException e) {
            LOG.warn("Entrega perdida antes de a mensagem seguir para a DLQ; o broker a reentregará: {}",
                    e.toString());
            message.turn.release();
            return;
        } catch (IOException e) {
            message.deadLetterFailures++;
            long delay = backoff.delayMillis(message.deadLetterFailures);
            LOG.warn("Publicação na DLQ não confirmada; nova tentativa em {} ms", delay, e);
            later(message, delay, () -> deadLetter(message, failure, attempts));
            return;
        }
        metrics.recordDeadLetter();
        blockedKeys.block(message.key);
        LOG.info("Mensagem publicada na DLQ ({}); chave '{}' bloqueada", failure, message.key);
        message.turn.release();
    }

    /** Repete o trabalho em uma thread de trabalho depois do intervalo, com a chave ainda ocupada. */
    private void later(InFlight message, long delayMillis, Runnable work) {
        scheduler.schedule(delayMillis, () -> {
            try {
                executor.dispatch(() -> inContext(message, work));
            } catch (RejectedExecutionException e) {
                // consumidor fechado: a mensagem permanece sem ack e o broker a reentrega
            }
        });
    }

    /** Executa o trabalho com o MDC da mensagem; uma falha inesperada libera a chave e deixa a mensagem sem ack. */
    private void inContext(InFlight message, Runnable work) {
        Map<String, Object> headers = message.delivery.headers();
        try (MessageMdc ignored = MessageMdc.open(
                EnvelopeReader.optionalText(headers, MessageMdc.MESSAGE_ID),
                EnvelopeReader.optionalText(headers, MessageMdc.CORRELATION_ID),
                EnvelopeReader.optionalText(headers, MessageMdc.AGGREGATE_KEY),
                EnvelopeReader.optionalText(headers, MessageMdc.TRACE_ID))) {
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                LOG.error("Falha inesperada no consumidor; a mensagem fica sem ack e o broker a reentregará", e);
                message.turn.release();
                if (e instanceof Error) {
                    throw (Error) e;
                }
            }
        }
    }

    private void requeue(Delivery delivery) {
        try {
            source.requeue(delivery);
        } catch (IOException e) {
            LOG.warn("Mensagem recebida durante o fechamento não foi devolvida; o broker a reentregará: {}",
                    e.toString());
        }
    }

    private static String keyOf(Delivery delivery) {
        String key = EnvelopeReader.optionalText(delivery.headers(), MessageMdc.AGGREGATE_KEY);
        return key == null || key.isEmpty() ? RESERVED_KEY : key;
    }
}
