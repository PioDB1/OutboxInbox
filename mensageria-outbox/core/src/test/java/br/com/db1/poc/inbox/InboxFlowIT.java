package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InboxBrokerIT;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fluxo do consumo com RabbitMQ e H2 em arquivo: leitura do envelope, efeito e ack, duplicata e prefetch. */
class InboxFlowIT extends InboxBrokerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path directory;

    private H2Db db;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private InboxConsumer startConsumer(InboxConfig config, MessageSource source, InboxMessageHandler handler) {
        db = H2Db.file(directory);
        InboxConsumer consumer = closeAtEnd(new InboxConsumer(config, source, new H2InboxDialect(),
                new JdbcInboxTransactionExecutor(db.dataSource()), handler, new InMemoryBlockedKeys(),
                new InboxMetrics(registry)));
        consumer.start();
        return consumer;
    }

    private void awaitSettled() {
        Await.until(TIMEOUT, "fila principal sem mensagens prontas nem sem ack", () -> {
            try {
                return readyMessages(queue) == 0 && unacknowledged(queue) == 0;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** TC-1: o tratador recebe o envelope publicado, com microssegundos, e o payload byte a byte. */
    @Test
    void envelopeLidoDosHeadersEPayloadIntacto() throws Exception {
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        Envelope published = TestEnvelopes.of("agregado-1", TestEnvelopes.TYPE,
                Instant.now().truncatedTo(ChronoUnit.MICROS), payload);
        AtomicReference<Envelope> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        startConsumer(config(3, 50, 4), directSource(), (envelope, connection) -> {
            calls.incrementAndGet();
            received.set(envelope);
        });

        publish(published);

        Await.until(TIMEOUT, "tratador chamado", () -> received.get() != null);
        awaitSettled();
        assertEquals(published, received.get());
        assertArrayEquals(payload, received.get().payload());
        assertEquals(1, calls.get());
    }

    /** TC-4: os headers de texto chegam como {@code String} e {@code schemaVersion} como número. */
    @Test
    void headersChegamComoString() throws Exception {
        List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<Delivery>());
        directSource().start(queue, deadLetterQueue, 10, deliveries::add);

        publish(TestEnvelopes.of("agregado-1"));

        Await.until(TIMEOUT, "entrega recebida", () -> deliveries.size() == 1);
        for (String name : new String[] {"messageId", "correlationId", "idempotencyKey", "aggregateKey", "type",
            "occurredAt", "traceId"}) {
            assertInstanceOf(String.class, deliveries.get(0).headers().get(name), name);
        }
        assertInstanceOf(Number.class, deliveries.get(0).headers().get("schemaVersion"));
    }

    /** TC-5 (com broker): efeito e inbox gravados juntos e o ack deixa a fila sem mensagens e sem mensagens sem ack. */
    @Test
    void fluxoFelizGravaEfeitoEInboxEConfirma() throws Exception {
        startConsumer(config(3, 50, 4), directSource(), (envelope, connection) ->
                H2Db.insertEffect(connection, envelope.messageId(), envelope.messageId().toString()));

        publish(TestEnvelopes.of("agregado-1"));

        Await.until(TIMEOUT, "efeito gravado", () -> db.count("efeito") == 1);
        awaitSettled();
        assertEquals(1, db.count("inbox"));
        assertEquals(1, db.count("efeito"));
    }

    /** TC-9 (com broker): a mensagem repetida é descartada com ack e conta como duplicata. */
    @Test
    void duplicataEDescartadaComAck() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startConsumer(config(3, 50, 4), directSource(), (envelope, connection) -> calls.incrementAndGet());
        Envelope envelope = TestEnvelopes.of("agregado-1");

        publish(envelope);
        publish(envelope);

        Await.until(TIMEOUT, "duplicata descartada",
                () -> registry.counter(InboxMetrics.DUPLICATES_DISCARDED).count() == 1.0);
        awaitSettled();
        assertEquals(1, calls.get());
        assertEquals(1, db.count("inbox"));
    }

    /** TC-34: o {@code prefetch_count} do consumidor é o limite de mensagens em voo, e nunca passa dele. */
    @Test
    void basicQosIgualAoLimiteEmVoo() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        startConsumer(config(3, 7, 4), directSource(), (envelope, connection) -> release.await(120, TimeUnit.SECONDS));
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            envelopes.add(TestEnvelopes.of("agregado-1"));
        }

        publishAll(envelopes);

        Await.until(TIMEOUT, "prefetch_count do consumidor igual a 7", () -> {
            try {
                return consumerPrefetch(queue) == 7;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Await.until(TIMEOUT, "7 mensagens sem ack", () -> {
            try {
                return unacknowledged(queue) == 7;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(93, readyMessages(queue));
        release.countDown();
    }
}
