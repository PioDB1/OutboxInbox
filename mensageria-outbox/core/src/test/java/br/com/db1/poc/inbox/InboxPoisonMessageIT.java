package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InboxBrokerIT;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mensagem veneno com RabbitMQ e H2 em arquivo: DLQ com headers originais e as seguintes da chave (A-F6). */
class InboxPoisonMessageIT extends InboxBrokerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final List<String> CONTRACT_HEADERS = Arrays.asList("messageId", "correlationId",
            "idempotencyKey", "aggregateKey", "type", "schemaVersion", "occurredAt", "traceId");

    @TempDir
    Path directory;

    private H2Db db;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void startConsumer(InboxConfig config, InboxMessageHandler handler) {
        db = H2Db.file(directory);
        InboxConsumer consumer = closeAtEnd(new InboxConsumer(config, directSource(), new H2InboxDialect(),
                new JdbcInboxTransactionExecutor(db.dataSource()), handler, new InMemoryBlockedKeys(),
                new InboxMetrics(registry)));
        consumer.start();
    }

    private void awaitSettled() {
        Await.until(TIMEOUT, "fila principal sem mensagens prontas nem sem ack", () -> {
            try {
                return readyMessages(queue) == 0 && unacknowledged(queue) == 0;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Envelope envelope(String idempotencyKey, String aggregateKey) {
        return Envelope.novo(UUID.randomUUID(), idempotencyKey, aggregateKey, TestEnvelopes.TYPE, (short) 1,
                Instant.now().truncatedTo(ChronoUnit.MICROS), new byte[] {9, 8, 7, 0, (byte) 255},
                "trace-" + idempotencyKey);
    }

    /** TC-19 e TC-22: depois de {@code max-attempts} a mensagem vai para a DLQ com corpo e headers originais. */
    @Test
    void mensagemVenenoVaiParaADlqComCorpoEHeadersOriginais() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startConsumer(config(3, 50, 2), (envelope, connection) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("veneno");
        });
        Envelope poison = envelope("veneno", "agregado-1");

        publish(poison);

        List<Received> dead = awaitMessages(deadLetterQueue, 1, TIMEOUT);
        awaitSettled();
        Received message = dead.get(0);
        assertArrayEquals(poison.payload(), message.body());
        assertEquals(poison.messageId().toString(), message.header("messageId"));
        assertEquals(poison.correlationId().toString(), message.header("correlationId"));
        assertEquals(poison.idempotencyKey(), message.header("idempotencyKey"));
        assertEquals(poison.aggregateKey(), message.header("aggregateKey"));
        assertEquals(poison.type(), message.header("type"));
        assertEquals("1", message.header("schemaVersion"));
        assertEquals(poison.traceId(), message.header("traceId"));
        for (String name : CONTRACT_HEADERS) {
            assertEquals(true, message.rawHeader(name) != null, name);
        }
        assertEquals("max-attempts", message.header("x-inbox-failure"));
        assertEquals("3", message.header("x-inbox-attempts"));
        assertEquals(3, calls.get());
        assertEquals(0, db.count("inbox"));
        assertEquals(1.0, registry.counter(InboxMetrics.DLQ_MESSAGES).count());
        assertEquals(2.0, registry.counter(InboxMetrics.PROCESS_RETRIES).count());
    }

    /** TC-20: as mensagens seguintes da chave vão para a DLQ sem o tratador; as outras chaves seguem. */
    @Test
    void mensagensSeguintesDaChaveVaoParaADlqEOutrasChavesSeguem() throws Exception {
        List<String> handled = Collections.synchronizedList(new ArrayList<String>());
        startConsumer(config(2, 50, 4), (envelope, connection) -> {
            handled.add(envelope.idempotencyKey());
            if (envelope.idempotencyKey().equals("A1")) {
                throw new IllegalStateException("veneno");
            }
            H2Db.insertEffect(connection, envelope.messageId(), envelope.idempotencyKey());
        });

        publish(envelope("A1", "A"));
        publish(envelope("A2", "A"));
        publish(envelope("A3", "A"));
        publish(envelope("B1", "B"));

        List<Received> dead = awaitMessages(deadLetterQueue, 3, TIMEOUT);
        Await.until(TIMEOUT, "B1 aplicada", () -> db.count("efeito") == 1);
        awaitSettled();
        assertEquals(Arrays.asList("A1", "A2", "A3"), Arrays.asList(dead.get(0).header("idempotencyKey"),
                dead.get(1).header("idempotencyKey"), dead.get(2).header("idempotencyKey")));
        assertEquals("max-attempts", dead.get(0).header("x-inbox-failure"));
        assertEquals("2", dead.get(0).header("x-inbox-attempts"));
        for (int i = 1; i < 3; i++) {
            assertEquals("blocked-key", dead.get(i).header("x-inbox-failure"));
            assertEquals("0", dead.get(i).header("x-inbox-attempts"));
        }
        List<String> handledSorted = new ArrayList<>(handled);
        Collections.sort(handledSorted);
        assertEquals(Arrays.asList("A1", "A1", "B1"), handledSorted);
        assertEquals(1, db.count("inbox"));
        assertEquals(3.0, registry.counter(InboxMetrics.DLQ_MESSAGES).count());
    }
}
