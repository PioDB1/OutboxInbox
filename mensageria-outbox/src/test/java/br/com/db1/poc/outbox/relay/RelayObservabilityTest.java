package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.metrics.OutboxMetrics;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.LogCapture;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

/**
 * Métricas e MDC do relay sobre uma outbox em memória (TC-35, TC-49, TC-52 e TC-53 sem Docker); os testes de
 * integração repetem os cenários contra SQL Server e RabbitMQ.
 */
class RelayObservabilityTest {

    private static final List<String> MDC_KEYS = List.of("messageId", "correlationId", "aggregateKey", "traceId");

    private final InMemoryDialect dialect = new InMemoryDialect();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Relay relay(RelayStrategyType strategy, ScriptedPublisher publisher) {
        Properties properties = new Properties();
        properties.setProperty("outbox.relay.enabled", "true");
        properties.setProperty("outbox.relay.strategy", strategy.value());
        properties.setProperty("outbox.relay.backoff.initial", "20ms");
        properties.setProperty("outbox.relay.backoff.max", "40ms");
        RelayConfig config = RelayConfig.fromProperties(properties);
        return switch (strategy) {
            case CLAIM -> new ClaimRelay(config, dialect.dataSource(), dialect, publisher, meters, "r");
            case READ_POINT -> new ReadPointRelay(config, dialect.dataSource(), dialect, publisher, meters, "r");
        };
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private static void runUntil(Relay relay, String description, BooleanSupplier condition) {
        Await.until(Duration.ofSeconds(10), description, () -> {
            relay.runOnce();
            return condition.getAsBoolean();
        });
    }

    /** TC-49: um ciclo sem linhas pendentes deixa as três métricas registradas com valor zero. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void metricasRegistradasComValorZeroSemPendentes(RelayStrategyType strategy) {
        Relay relay = relay(strategy, new ScriptedPublisher());

        assertEquals(0, relay.runOnce());

        assertEquals(0.0, gauge(OutboxMetrics.LAG_SECONDS));
        assertEquals(0.0, gauge(OutboxMetrics.PENDING));
        assertEquals(0.0, meters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void pendentesAcompanhamFalhasEVoltamAZeroNaPublicacao(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        dialect.add(InMemoryDialect.envelope("B"));
        ScriptedPublisher publisher = new ScriptedPublisher().failAll();
        Relay relay = relay(strategy, publisher);

        relay.runOnce();

        assertEquals(2.0, gauge(OutboxMetrics.PENDING));

        publisher.stopFailing();
        runUntil(relay, "duas linhas publicadas", () -> publisher.delivered().size() == 2);

        assertEquals(0.0, gauge(OutboxMetrics.PENDING));
        assertEquals(0.0, gauge(OutboxMetrics.LAG_SECONDS));
    }

    /** TC-35: o contador cresce a cada tentativa posterior à primeira falha e não na primeira tentativa. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void contadorDeRetryCresceSoDepoisDaPrimeiraFalha(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        AtomicInteger failures = new AtomicInteger(3);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> failures.getAndDecrement() > 0);
        Relay relay = relay(strategy, publisher);

        relay.runOnce();

        assertEquals(1, publisher.attempts().size());
        assertEquals(0.0, meters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());

        runUntil(relay, "linha publicada", () -> publisher.delivered().size() == 1);

        assertEquals(4, publisher.attempts().size());
        assertEquals(3.0, meters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());
    }

    /** TC-52: todo log do relay sobre a linha traz as quatro chaves do MDC com os valores do envelope. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void logsDaLinhaTrazemOMdcDoEnvelope(RelayStrategyType strategy) {
        Envelope envelope = new Envelope(UUID.randomUUID(), UUID.randomUUID(), "idem-1", "agg-1",
                "PagamentoRecebido", (short) 1, Instant.now(), new byte[] {1}, "trace-conhecido");
        dialect.add(envelope);
        AtomicInteger failures = new AtomicInteger(1);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> failures.getAndDecrement() > 0);
        Relay relay = relay(strategy, publisher);

        try (LogCapture logs = new LogCapture(RelayRunner.class)) {
            runUntil(relay, "linha publicada", () -> publisher.delivered().size() == 1);

            List<ILoggingEvent> events = logs.events();
            assertTrue(events.size() >= 2, "um log da falha e um da publicação: " + events);
            Map<String, String> expected = new HashMap<>();
            expected.put("messageId", envelope.messageId().toString());
            expected.put("correlationId", envelope.correlationId().toString());
            expected.put("aggregateKey", "agg-1");
            expected.put("traceId", "trace-conhecido");
            for (ILoggingEvent event : events) {
                Map<String, String> mdc = event.getMDCPropertyMap();
                expected.forEach((key, value) -> assertEquals(value, mdc.get(key),
                        key + " no MDC de: " + event.getFormattedMessage()));
            }
        }
    }

    /** TC-53: o MDC vale durante a tentativa de publicação e é limpo ao fim. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void mdcELimpoAoFimDaTentativa(RelayStrategyType strategy) {
        Envelope envelope = InMemoryDialect.envelope("agg-1");
        dialect.add(envelope);
        Map<String, String> duringPublish = new HashMap<>();
        ScriptedPublisher publisher = new ScriptedPublisher().before(message ->
                MDC_KEYS.forEach(key -> duringPublish.put(key, MDC.get(key))));
        Relay relay = relay(strategy, publisher);

        assertEquals(1, relay.runOnce());

        assertEquals(envelope.messageId().toString(), duringPublish.get("messageId"));
        assertFalse(duringPublish.containsValue(null), "MDC durante a tentativa: " + duringPublish);
        MDC_KEYS.forEach(key -> assertNull(MDC.get(key), key + " permaneceu no MDC"));
    }
}
