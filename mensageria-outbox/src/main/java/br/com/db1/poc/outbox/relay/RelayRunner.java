package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.lease.LeaseManager;
import br.com.db1.poc.outbox.metrics.OutboxMetrics;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.retention.OutboxRetention;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Esqueleto comum às duas estratégias: thread própria, laço de ciclos, lease, retenção, backoff e métricas. Cada
 * estratégia implementa {@link #publishCycle()}, que lê e publica um lote.
 *
 * <p>Todo estado de que o relay precisa para retomar (linhas pendentes, lease, ponto de leitura) vive no banco; o
 * que a instância guarda em memória (backoff por {@code aggregateKey}, linhas publicadas à frente do ponto de
 * leitura) é limitado ao tamanho do lote e apenas evita retrabalho.
 */
abstract class RelayRunner implements Relay {

    /** Resultado da tentativa de publicar uma linha. */
    protected enum Outcome {
        PUBLISHED,
        /** Falha restrita à mensagem: as linhas de outras chaves seguem. */
        FAILED,
        /** Falha de conexão: o lote termina naquele ponto. */
        ABORT
    }

    private static final Logger LOG = LoggerFactory.getLogger(RelayRunner.class);

    private static final Duration MIN_WAIT = Duration.ofMillis(1);

    protected final RelayConfig config;
    protected final DataSource dataSource;
    protected final OutboxDialect dialect;
    protected final BackoffPolicy backoff;

    private final OutboxMetrics metrics;
    private final PendingQuery pendingQuery;
    private final MessagePublisher publisher;
    private final LeaseManager lease;
    private final OutboxRetention retention;
    private final Object lifecycleLock = new Object();

    private Thread thread;
    private volatile boolean running;
    private volatile boolean closed;

    protected RelayRunner(RelayConfig config, DataSource dataSource, OutboxDialect dialect,
            MessagePublisher publisher, MeterRegistry meterRegistry, String holderId) {
        this.config = config;
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.publisher = publisher;
        this.metrics = new OutboxMetrics(meterRegistry);
        this.pendingQuery = PendingQuery.create(dialect);
        this.backoff = new BackoffPolicy(config.backoffInitial(), config.backoffMultiplier(), config.backoffMax(),
                config.batchSize());
        this.lease = new LeaseManager(dialect, dataSource, holderId, config.leaseDuration(),
                config.leaseRenewInterval());
        this.retention = new OutboxRetention(dialect, dataSource, config.strategy(), config.retentionMaxAge(),
                config.retentionInterval(), config.retentionBatchSize());
    }

    /** Lê e publica um lote, com o lease já garantido; devolve as linhas publicadas. */
    protected abstract int publishCycle() throws SQLException;

    @Override
    public void start() {
        if (!config.enabled()) {
            return;
        }
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("O relay já foi encerrado");
            }
            if (thread != null) {
                return;
            }
            running = true;
            thread = new Thread(this::loop, "outbox-relay-" + lease.holderId());
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public final synchronized int runOnce() {
        if (!config.enabled() || closed) {
            return 0;
        }
        try {
            if (!lease.ensureHeld()) {
                return 0;
            }
            int published = publishCycle();
            runRetention();
            refreshMetrics();
            return published;
        } catch (SQLException e) {
            throw new RelayException("Falha de acesso ao banco no ciclo do relay", e);
        }
    }

    @Override
    public void close() {
        Thread stopping;
        synchronized (lifecycleLock) {
            closed = true;
            running = false;
            stopping = thread;
            thread = null;
        }
        if (stopping != null) {
            stopping.interrupt();
            try {
                stopping.join(config.confirmTimeout().plusSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            lease.release();
        } catch (SQLException e) {
            LOG.warn("Falha ao liberar o lease do relay; ele expira sozinho", e);
        }
    }

    /** {@code true} enquanto este relay deve continuar publicando: ativo e detentor do lease. */
    protected final boolean leaseValid() {
        if (closed) {
            return false;
        }
        try {
            return lease.ensureHeld();
        } catch (SQLException e) {
            LOG.warn("Falha ao renovar o lease do relay", e);
            return false;
        }
    }

    /**
     * Publica a linha e atualiza o backoff da chave. A tentativa roda com {@code messageId}, {@code correlationId},
     * {@code aggregateKey} e {@code traceId} no MDC, removidos ao fim.
     */
    protected final Outcome publish(OutboxRow row) {
        Envelope envelope = row.envelope();
        String key = row.aggregateKey();
        try (MDC.MDCCloseable messageId = MDC.putCloseable("messageId", envelope.messageId().toString());
                MDC.MDCCloseable correlationId = MDC.putCloseable("correlationId",
                        envelope.correlationId().toString());
                MDC.MDCCloseable aggregateKey = MDC.putCloseable("aggregateKey", key);
                MDC.MDCCloseable traceId = MDC.putCloseable("traceId", envelope.traceId())) {
            if (backoff.failures(key) > 0) {
                metrics.recordRetry();
            }
            try {
                publisher.publish(PublishableMessage.from(envelope));
            } catch (PublishException e) {
                Duration delay = backoff.recordFailure(key);
                LOG.warn("Falha ao publicar a mensagem; nova tentativa em {} ms: {}", delay.toMillis(),
                        e.getMessage());
                return e.kind() == PublishException.Kind.CONNECTION ? Outcome.ABORT : Outcome.FAILED;
            }
            backoff.recordSuccess(key);
            LOG.debug("Mensagem publicada e confirmada pelo broker");
            return Outcome.PUBLISHED;
        }
    }

    private void runRetention() {
        try {
            retention.runIfDue(this::leaseValid);
        } catch (SQLException e) {
            LOG.warn("Falha na retenção da outbox", e);
        }
    }

    private void refreshMetrics() {
        try {
            PendingStats stats = pendingQuery.pending(dataSource, config.strategy());
            metrics.update(stats.count(), stats.oldestAge());
        } catch (SQLException e) {
            LOG.warn("Falha ao consultar as linhas pendentes para as métricas", e);
        }
    }

    private void loop() {
        while (running) {
            int published = 0;
            try {
                published = runOnce();
            } catch (RuntimeException e) {
                LOG.error("Falha no ciclo do relay", e);
            }
            if (published == 0 && running && !pause(idleWait())) {
                return;
            }
        }
    }

    /** Espera entre ciclos sem trabalho: o menor entre o poll, a renovação do lease e o fim do backoff mais próximo. */
    private synchronized Duration idleWait() {
        Duration wait = config.pollInterval().compareTo(config.leaseRenewInterval()) <= 0
                ? config.pollInterval()
                : config.leaseRenewInterval();
        Optional<Duration> untilRetry = backoff.untilNextAttempt();
        if (untilRetry.isPresent() && untilRetry.get().compareTo(wait) < 0) {
            wait = untilRetry.get();
        }
        return wait.compareTo(MIN_WAIT) < 0 ? MIN_WAIT : wait;
    }

    private boolean pause(Duration wait) {
        try {
            Thread.sleep(wait.toMillis(), wait.toNanosPart() % 1_000_000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
