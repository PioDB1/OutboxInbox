package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.support.SqlServerIT;
import br.com.db1.poc.shared.support.HeapProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Carga com memória limitada (TC-61): o relay publica 10 000 linhas pendentes em lotes de 100 dentro dos limites
 * de heap e de memória direta do {@code argLine} do failsafe ({@code -Xmx256m -XX:MaxDirectMemorySize=64m}). O
 * publicador de teste só conta as mensagens, de modo que o heap medido é o do relay e do acesso a banco.
 *
 * <p>Nenhum número desta classe é meta de desempenho; o limite de 32 MiB de crescimento entre o segundo e o
 * terceiro ponto de medição verifica que o consumo não acompanha o volume processado.
 */
class RelayMemoryIT extends SqlServerIT {

    private static final int TOTAL = 10_000;
    private static final int BATCH_SIZE = 100;
    private static final long MAX_GROWTH = 32 * HeapProbe.MEBIBYTE;
    private static final int[] MEASUREMENT_POINTS = {2_000, 6_000, TOTAL};

    /** Publicador que conta as mensagens e não guarda nenhuma. */
    private static final class CountingPublisher implements MessagePublisher {

        private final AtomicLong published = new AtomicLong();

        @Override
        public void publish(PublishableMessage message) {
            published.incrementAndGet();
        }

        @Override
        public void close() {
        }
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void relayPublicaVolumeMaiorQueOLoteSemCrescerEmMemoria(RelayStrategyType strategy) {
        insertPending();
        assertEquals(TOTAL, db.pendingCount(strategy));
        Properties properties = new Properties();
        properties.setProperty("outbox.relay.enabled", "true");
        properties.setProperty("outbox.relay.strategy", strategy.value());
        properties.setProperty("outbox.relay.batch-size", String.valueOf(BATCH_SIZE));
        properties.setProperty("outbox.relay.poll-interval", "50ms");
        CountingPublisher publisher = new CountingPublisher();
        long[] heap = new long[MEASUREMENT_POINTS.length];

        try (Relay relay = RelayFactory.create(RelayConfig.fromProperties(properties), dataSource, publisher,
                new SimpleMeterRegistry(), "relay-carga")) {
            int measured = 0;
            while (measured < MEASUREMENT_POINTS.length) {
                long before = publisher.published.get();
                relay.runOnce();
                assertTrue(publisher.published.get() > before, "o ciclo não publicou nenhuma linha pendente");
                if (publisher.published.get() >= MEASUREMENT_POINTS[measured]) {
                    heap[measured++] = HeapProbe.usedAfterGc();
                }
            }
        }

        assertEquals(TOTAL, publisher.published.get());
        assertEquals(0, db.pendingCount(strategy));
        System.out.printf("%s: heap usado após %s linhas publicadas = %s MiB%n", strategy.value(),
                Arrays.toString(MEASUREMENT_POINTS),
                Arrays.stream(heap).mapToObj(h -> String.valueOf(h / HeapProbe.MEBIBYTE)).collect(Collectors.toList()));
        long growth = heap[2] - heap[1];
        assertTrue(growth <= MAX_GROWTH, "o heap cresceu " + growth / HeapProbe.MEBIBYTE
                + " MiB entre o segundo e o terceiro ponto de medição");
    }

    /** Insere as linhas pendentes em uma única instrução, com 500 {@code aggregateKey} e payload de 1 KiB. */
    private void insertPending() {
        db.execute("INSERT INTO outbox (message_id, correlation_id, idempotency_key, aggregate_key, type, schema_ver,\n"
                + "                    occurred_at, trace_id, payload)\n"
                + "SELECT NEWID(), NEWID(), CONCAT('idem-', n), CONCAT('agg-', n % 500), 'PagamentoRecebido', 1,\n"
                + "       SYSUTCDATETIME(), 'trace-carga',\n"
                + "       CAST(REPLICATE(CAST('x' AS VARCHAR(MAX)), 1024) AS VARBINARY(MAX))\n"
                + "  FROM (SELECT TOP (" + TOTAL + ") ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n\n"
                + "          FROM sys.all_objects a CROSS JOIN sys.all_objects b) AS numbers\n"
                + " ORDER BY n");
    }
}
