package br.com.db1.poc.outbox.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);

    @Test
    void registraAsTresMetricasComValorZero() {
        assertEquals(0.0, registry.get(OutboxMetrics.LAG_SECONDS).gauge().value());
        assertEquals(0.0, registry.get(OutboxMetrics.PENDING).gauge().value());
        assertEquals(0.0, registry.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());
    }

    @Test
    void gaugesRefletemOUltimoEstadoInformado() {
        metrics.update(7, Duration.ofMillis(2_500));

        assertEquals(2.5, registry.get(OutboxMetrics.LAG_SECONDS).gauge().value());
        assertEquals(7.0, registry.get(OutboxMetrics.PENDING).gauge().value());

        metrics.update(0, Duration.ZERO);

        assertEquals(0.0, registry.get(OutboxMetrics.LAG_SECONDS).gauge().value());
        assertEquals(0.0, registry.get(OutboxMetrics.PENDING).gauge().value());
    }

    @Test
    void contadorDeRetryCresceACadaRegistro() {
        metrics.recordRetry();
        metrics.recordRetry();

        assertEquals(2.0, registry.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());
    }
}
