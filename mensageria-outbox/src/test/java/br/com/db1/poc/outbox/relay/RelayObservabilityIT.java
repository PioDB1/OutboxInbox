package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.metrics.OutboxMetrics;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.LogCapture;
import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Métricas de publicação, consulta de pendentes e logs estruturados do relay (TC-35, TC-50 a TC-52). */
class RelayObservabilityIT extends RelayIT {

    private final PendingQuery pendingQuery = PendingQuery.create(new SqlServerOutboxDialect());

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    /** TC-35: o contador de retry cresce a cada tentativa posterior à primeira falha da linha. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void contadorDeRetryCresceSoDepoisDaPrimeiraFalha(RelayStrategyType strategy) {
        db.insert(TestEnvelopes.of("agg-1"));
        AtomicInteger failures = new AtomicInteger(3);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> failures.getAndDecrement() > 0);
        Relay relay = relay(strategy, publisher, "backoff.initial", "50ms", "backoff.max", "100ms");

        relay.runOnce();

        assertEquals(1, publisher.attempts().size());
        assertEquals(0.0, meters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());

        runUntil(relay, "linha publicada", () -> db.pendingCount(strategy) == 0);

        assertEquals(4, publisher.attempts().size());
        assertEquals(3.0, meters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count());
    }

    /** TC-50: com o broker fora, pendentes e lag crescem; depois da publicação, voltam a zero. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void lagEPendentesCrescemComOBrokerForaEVoltamAZero(RelayStrategyType strategy) throws IOException {
        broker.proxy().disable();
        int total = 5;
        try (OutboxDb.Transaction transaction = db.open()) {
            for (int i = 0; i < total; i++) {
                transaction.insert(TestEnvelopes.of("agg-" + i));
            }
            transaction.commit();
        }
        Relay relay = relay(strategy, new ScriptedPublisher(rabbitPublisher()), "backoff.initial", "100ms",
                "backoff.max", "500ms");

        relay.runOnce();
        Await.until(TIMEOUT, "lag acima de zero com " + total + " pendentes", () -> {
            relay.runOnce();
            return gauge(OutboxMetrics.LAG_SECONDS) > 0.0;
        });

        assertEquals(total, gauge(OutboxMetrics.PENDING));
        double firstLag = gauge(OutboxMetrics.LAG_SECONDS);
        Await.until(TIMEOUT, "lag crescente", () -> {
            relay.runOnce();
            return gauge(OutboxMetrics.LAG_SECONDS) > firstLag;
        });

        broker.proxy().enable();
        runUntil(relay, Duration.ofSeconds(60), "todas as linhas publicadas", () -> db.pendingCount(strategy) == 0);
        relay.runOnce();

        assertEquals(0.0, gauge(OutboxMetrics.PENDING));
        assertEquals(0.0, gauge(OutboxMetrics.LAG_SECONDS));
        assertEquals(total, awaitMessages(total, TIMEOUT).size());
    }

    /** TC-51: a consulta devolve a contagem e a idade da linha pendente mais antiga. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void pendentesDevolveContagemEIdadeDaLinhaMaisAntiga(RelayStrategyType strategy) throws SQLException {
        long firstInsertedAt = System.nanoTime();
        db.insert(TestEnvelopes.of("agg-1"));
        Await.until(Duration.ofSeconds(5), "intervalo entre as inserções", () ->
                System.nanoTime() - firstInsertedAt >= Duration.ofMillis(200).toNanos());
        db.insert(TestEnvelopes.of("agg-2"));

        PendingStats stats = pendingQuery.pending(dataSource, strategy);

        assertEquals(2, stats.count());
        Duration sinceFirstInsert = Duration.ofNanos(System.nanoTime() - firstInsertedAt);
        assertTrue(stats.oldestAge().compareTo(Duration.ofMillis(200)) >= 0, "idade: " + stats.oldestAge());
        assertTrue(stats.oldestAge().compareTo(sinceFirstInsert.plusSeconds(5)) <= 0, "idade: " + stats.oldestAge());
    }

    /** Sem linhas pendentes a consulta devolve contagem zero e idade zero. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void pendentesSemLinhasDevolveZero(RelayStrategyType strategy) throws SQLException {
        PendingStats stats = pendingQuery.pending(dataSource, strategy);

        assertEquals(0, stats.count());
        assertEquals(Duration.ZERO, stats.oldestAge());
    }

    /** A consulta não espera nem conta as linhas de uma transação ainda aberta. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void pendentesIgnoraLinhaDeTransacaoAberta(RelayStrategyType strategy) throws SQLException {
        db.insert(TestEnvelopes.of("agg-1"));
        try (OutboxDb.Transaction open = db.open()) {
            open.insert(TestEnvelopes.of("agg-2"));

            assertEquals(1, pendingQuery.pending(dataSource, strategy).count());

            open.commit();
        }
        assertEquals(2, pendingQuery.pending(dataSource, strategy).count());
    }

    /** TC-52: a mensagem leva {@code correlationId} e {@code traceId} nos headers, e os logs da linha os levam no MDC. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void correlationIdETraceIdNosHeadersENosLogs(RelayStrategyType strategy) {
        Envelope envelope = new Envelope(UUID.randomUUID(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                "agg-1", TestEnvelopes.TYPE, (short) 1, Instant.now().truncatedTo(ChronoUnit.MICROS),
                new byte[] {1, 2, 3}, "trace-conhecido");
        db.insert(envelope);
        Relay relay = relay(strategy, rabbitPublisher());

        List<ILoggingEvent> events = new ArrayList<>();
        try (LogCapture logs = new LogCapture(RelayRunner.class)) {
            runUntil(relay, "linha publicada", () -> db.isSent(strategy, envelope.messageId()));
            events.addAll(logs.events());
        }

        Received received = awaitMessages(1, TIMEOUT).get(0);
        assertEquals(envelope.correlationId().toString(), received.header("correlationId"));
        assertEquals("trace-conhecido", received.header("traceId"));
        assertFalse(events.isEmpty(), "nenhum log do relay sobre a linha");
        for (ILoggingEvent event : events) {
            assertEquals(envelope.messageId().toString(), event.getMDCPropertyMap().get("messageId"));
            assertEquals(envelope.correlationId().toString(), event.getMDCPropertyMap().get("correlationId"));
            assertEquals("agg-1", event.getMDCPropertyMap().get("aggregateKey"));
            assertEquals("trace-conhecido", event.getMDCPropertyMap().get("traceId"));
        }
    }
}
