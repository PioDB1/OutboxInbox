package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.DelegatingMessageSource;
import br.com.db1.poc.inbox.support.InboxBrokerIT;
import br.com.db1.poc.inbox.support.InboxSqlServerIT;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.HeapProbe;
import br.com.db1.poc.shared.support.TestEnvelopes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Carga com memória limitada (TC-33): o consumidor aplica 10 000 mensagens de 100 chaves dentro dos limites de heap
 * e de memória direta do {@code argLine} do failsafe ({@code -Xmx256m -XX:MaxDirectMemorySize=64m}), com
 * {@code max-in-flight} 50 e um tratador que só grava a inbox.
 *
 * <p>Nenhum número desta classe é meta de desempenho; o limite de 32 MiB de crescimento entre o segundo e o
 * terceiro ponto de medição verifica que o consumo não acompanha o volume processado.
 */
class InboxMemoryIT extends InboxBrokerIT {

    private static final int TOTAL = 10_000;
    private static final int KEYS = 100;
    private static final int MAX_IN_FLIGHT = 50;
    private static final long MAX_GROWTH = 32 * HeapProbe.MEBIBYTE;
    private static final int[] MEASUREMENT_POINTS = {2_000, 6_000, TOTAL};

    private DataSource dataSource;

    @BeforeEach
    void prepareSqlServer() throws SQLException {
        dataSource = InboxSqlServerIT.preparedDataSource();
        InboxSqlServerIT.clean(dataSource);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void consumidorAplicaVolumeMaiorQueOLimiteSemCrescerEmMemoria() throws Exception {
        List<Envelope> envelopes = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            envelopes.add(Envelope.novo(UUID.randomUUID(), "idem-" + i, "chave-" + (i % KEYS), TestEnvelopes.TYPE,
                    (short) 1, Instant.now().truncatedTo(ChronoUnit.MICROS), new byte[64], "trace-" + i));
        }
        publishAll(envelopes);

        AtomicInteger applied = new AtomicInteger();
        AtomicLong received = new AtomicLong();
        AtomicLong acked = new AtomicLong();
        AtomicLong maxUnacked = new AtomicLong();
        MessageSource observed = new DelegatingMessageSource(directSource()) {
            @Override
            protected void onReceived(Delivery delivery) {
                long unacked = received.incrementAndGet() - acked.get();
                maxUnacked.accumulateAndGet(unacked, Math::max);
            }

            @Override
            protected void beforeAck(Delivery delivery) {
                acked.incrementAndGet();
            }
        };
        long[] heap = new long[MEASUREMENT_POINTS.length];

        try (InboxConsumer consumer = new InboxConsumer(config(3, MAX_IN_FLIGHT, 8), observed,
                new SqlServerInboxDialect(), new JdbcInboxTransactionExecutor(dataSource),
                (envelope, connection) -> applied.incrementAndGet(), new InMemoryBlockedKeys(),
                new InboxMetrics(new SimpleMeterRegistry()))) {
            consumer.start();
            int measured = 0;
            while (measured < MEASUREMENT_POINTS.length) {
                final int target = MEASUREMENT_POINTS[measured];
                Await.until(Duration.ofMinutes(10), target + " mensagens aplicadas", () -> applied.get() >= target);
                heap[measured++] = HeapProbe.usedAfterGc();
            }
            Await.until(Duration.ofMinutes(2), "todas as mensagens confirmadas", () -> acked.get() == TOTAL);
        }

        assertEquals(TOTAL, applied.get());
        assertEquals(TOTAL, InboxSqlServerIT.count(dataSource, "inbox"));
        assertTrue(maxUnacked.get() <= MAX_IN_FLIGHT, "mensagens sem ack: " + maxUnacked.get());
        System.out.printf("inbox: heap usado após %s mensagens aplicadas = %s MiB; máximo sem ack = %d%n",
                Arrays.toString(MEASUREMENT_POINTS),
                Arrays.stream(heap).mapToObj(bytes -> String.valueOf(bytes / HeapProbe.MEBIBYTE))
                        .collect(java.util.stream.Collectors.toList()),
                maxUnacked.get());
        long growth = heap[2] - heap[1];
        assertTrue(growth < MAX_GROWTH, "crescimento de heap entre o 2º e o 3º ponto: " + growth + " bytes");
    }
}
