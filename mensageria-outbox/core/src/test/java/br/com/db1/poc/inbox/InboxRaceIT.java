package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.InboxSqlServerIT;
import br.com.db1.poc.inbox.support.ScriptedMessageSource;
import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Corrida do mesmo {@code messageId} em chaves diferentes sobre SQL Server (R6). */
class InboxRaceIT extends InboxSqlServerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private void race(int deliveries, int workers) throws Exception {
        AtomicInteger handled = new AtomicInteger();
        ScriptedMessageSource source = new ScriptedMessageSource();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxConfig config = new InboxConfig("fila.teste", "fila.teste.dlq", 3, Duration.ofMillis(20), 2.0,
                Duration.ofMillis(200), 100, workers, Duration.ofSeconds(5));
        try (InboxConsumer consumer = new InboxConsumer(config, source, new SqlServerInboxDialect(),
                new JdbcInboxTransactionExecutor(dataSource), (envelope, connection) -> {
                    handled.incrementAndGet();
                    insertEffect(connection, envelope.messageId(), "efeito");
                    Thread.sleep(200);
                }, new InMemoryBlockedKeys(), new InboxMetrics(registry))) {
            consumer.start();
            Envelope first = TestEnvelopes.of("chave-0");
            for (int i = 0; i < deliveries; i++) {
                source.deliver(SimulatedDelivery.of(TestEnvelopes.of("chave-" + i))
                        .with("messageId", first.messageId().toString()));
            }

            Await.until(TIMEOUT, deliveries + " acks", () -> source.acked().size() == deliveries);

            assertEquals(1, handled.get());
            assertEquals(1, count(dataSource, "efeito"));
            assertEquals(1, count(dataSource, "inbox"));
            assertEquals(deliveries - 1.0, registry.counter(InboxMetrics.DUPLICATES_DISCARDED).count());
            assertEquals(0.0, registry.counter(InboxMetrics.PROCESS_RETRIES).count());
            assertEquals(0, source.deadLettered().size());
        }
    }

    /** TC-10 (SQL Server): duas entregas simultâneas; uma aplica e a outra é descartada, sem retry e sem DLQ. */
    @Test
    void duasEntregasSimultaneasAplicamUmaVez() throws Exception {
        race(2, 2);
    }

    @Test
    void vinteEntregasSimultaneasAplicamUmaVez() throws Exception {
        race(20, 8);
    }
}
