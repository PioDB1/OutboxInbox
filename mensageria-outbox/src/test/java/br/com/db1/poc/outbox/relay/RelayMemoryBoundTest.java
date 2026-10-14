package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.support.HeapProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Estado que o relay guarda por linha processada, sobre uma outbox em memória: o heap medido entre dois pontos do
 * processamento de 10 000 linhas em lotes de 100 não cresce com o volume. O teste de integração
 * {@code RelayMemoryIT} repete a medição contra SQL Server dentro dos limites de memória do failsafe.
 */
class RelayMemoryBoundTest {

    private static final int TOTAL = 10_000;
    private static final int[] MEASUREMENT_POINTS = {2_000, 6_000, TOTAL};
    private static final long MAX_GROWTH = 32 * HeapProbe.MEBIBYTE;

    /** Publicador que confirma e descarta cada mensagem. */
    private static final class DiscardingPublisher implements MessagePublisher {

        @Override
        public void publish(PublishableMessage message) {
        }

        @Override
        public void close() {
        }
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void estadoDoRelayNaoCresceComOVolumeProcessado(RelayStrategyType strategy) {
        InMemoryDialect dialect = new InMemoryDialect();
        for (int i = 0; i < TOTAL; i++) {
            dialect.add(InMemoryDialect.envelope("agg-" + (i % 500)));
        }
        Properties properties = new Properties();
        properties.setProperty("outbox.relay.enabled", "true");
        properties.setProperty("outbox.relay.strategy", strategy.value());
        properties.setProperty("outbox.relay.batch-size", "100");
        RelayConfig config = RelayConfig.fromProperties(properties);
        MessagePublisher publisher = new DiscardingPublisher();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Relay relay = switch (strategy) {
            case CLAIM -> new ClaimRelay(config, dialect.dataSource(), dialect, publisher, meters, "r");
            case READ_POINT -> new ReadPointRelay(config, dialect.dataSource(), dialect, publisher, meters, "r");
        };

        long published = 0;
        long[] heap = new long[MEASUREMENT_POINTS.length];
        int measured = 0;
        while (measured < MEASUREMENT_POINTS.length) {
            int cycle = relay.runOnce();
            assertTrue(cycle > 0, "o ciclo não publicou nenhuma linha pendente");
            published += cycle;
            if (published >= MEASUREMENT_POINTS[measured]) {
                heap[measured++] = HeapProbe.usedAfterGc();
            }
        }

        assertEquals(TOTAL, published);
        long growth = heap[2] - heap[1];
        assertTrue(growth <= MAX_GROWTH, "o heap cresceu " + growth / HeapProbe.MEBIBYTE
                + " MiB entre o segundo e o terceiro ponto de medição");
    }
}
