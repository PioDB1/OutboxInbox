package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.metrics.OutboxMetrics;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Coleta as observações de cada estratégia para a seção "Observações da PoC" do ADR {@code 0001} e as acrescenta
 * a {@code target/relay-observations.txt}. Os valores são observação, não limite de aprovação: as únicas
 * asserções verificam que nenhuma mensagem foi perdida.
 *
 * <p>Cenários: commit fora da ordem de {@code seq}; broker indisponível e restaurado (pendentes, lag, retries e
 * tempo de recuperação); bloqueios de linha mantidos no banco durante a publicação de um lote.
 */
class RelayObservationsIT extends RelayIT {

    private static final Path REPORT = Paths.get("target", "relay-observations.txt");
    private static final int OUTAGE_ROWS = 50;
    private static final int LOCK_BATCH = 20;
    private static final String ROW_LOCKS = "SELECT COUNT(*) FROM sys.dm_tran_locks\n"
            + " WHERE resource_database_id = DB_ID() AND resource_type IN ('KEY', 'RID')\n"
            + "   AND request_status = 'GRANT' AND request_mode IN ('U', 'X')\n"
            + "   AND request_session_id <> @@SPID";

    private static double gauge(MeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void coletaObservacoesDaEstrategia(RelayStrategyType strategy) throws IOException {
        StringBuilder report = new StringBuilder("estrategia=" + strategy.value() + "\n");
        outOfOrderCommits(strategy, report);
        brokerOutage(strategy, report);
        rowLocksDuringPublication(strategy, report);
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, report.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.println(report);
    }

    /** T1 insere (seq menor) e permanece aberta; T2 insere (seq maior) e confirma antes; depois T1 confirma. */
    private void outOfOrderCommits(RelayStrategyType strategy, StringBuilder report) {
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relayAs(strategy, publisher, "observacoes-ordem");
        Envelope fromT1 = TestEnvelopes.of("agg-t1");
        Envelope fromT2 = TestEnvelopes.of("agg-t2");
        try (OutboxDb.Transaction t1 = db.open()) {
            t1.insert(fromT1);
            db.insert(fromT2);
            for (int i = 0; i < 5; i++) {
                relay.runOnce();
            }
            report.append("commit fora de ordem: publicadas com T1 aberta=").append(publisher.delivered().size())
                    .append('\n');
            t1.commit();
        }
        runUntil(relay, "as duas linhas publicadas", () -> publisher.delivered().size() >= 2);
        Set<UUID> delivered = new HashSet<>();
        publisher.delivered().forEach(m -> delivered.add(m.messageId()));
        assertEquals(new HashSet<>(Arrays.asList(fromT1.messageId(), fromT2.messageId())), delivered);
        report.append("commit fora de ordem: mensagem de T1 pulada=nao, publicadas ao final=")
                .append(delivered.size()).append('\n');
        db.execute("DELETE FROM outbox");
        db.execute("DELETE FROM outbox_read_point");
    }

    /** Broker fora com {@value #OUTAGE_ROWS} linhas inseridas; mede o pico das métricas e o tempo até zerar. */
    private void brokerOutage(RelayStrategyType strategy, StringBuilder report) throws IOException {
        broker.proxy().disable();
        try (OutboxDb.Transaction transaction = db.open()) {
            for (int i = 0; i < OUTAGE_ROWS; i++) {
                transaction.insert(TestEnvelopes.of("agg-" + (i % 10)));
            }
            transaction.commit();
        }
        MeterRegistry outageMeters = new SimpleMeterRegistry();
        Relay relay = relayAs(strategy, new ScriptedPublisher(rabbitPublisher()), outageMeters, "observacoes-queda",
                "backoff.initial", "100ms", "backoff.max", "1s");
        Await.until(TIMEOUT, "lag acima de zero durante a queda", () -> {
            relay.runOnce();
            return gauge(outageMeters, OutboxMetrics.LAG_SECONDS) > 0.0;
        });
        for (int i = 0; i < 10; i++) {
            relay.runOnce();
        }
        double peakPending = gauge(outageMeters, OutboxMetrics.PENDING);
        double peakLag = gauge(outageMeters, OutboxMetrics.LAG_SECONDS);

        broker.proxy().enable();
        long restoredAt = System.nanoTime();
        runUntil(relay, Duration.ofSeconds(60), "todas as linhas enviadas", () -> db.pendingCount(strategy) == 0);
        Duration recovery = Duration.ofNanos(System.nanoTime() - restoredAt);
        relay.runOnce();

        report.append("queda do broker: linhas inseridas=").append(OUTAGE_ROWS)
                .append(", pico de pendentes=").append((long) peakPending)
                .append(", pico de lag(s)=").append(peakLag)
                .append(", retries=").append((long) outageMeters.get(OutboxMetrics.PUBLISH_RETRIES).counter().count())
                .append(", tempo de recuperação(ms)=").append(recovery.toMillis())
                .append(", pendentes ao final=").append((long) gauge(outageMeters, OutboxMetrics.PENDING)).append('\n');
        assertEquals(0, db.pendingCount(strategy));
        relay.close();
        awaitMessages(OUTAGE_ROWS, TIMEOUT);
        db.execute("DELETE FROM outbox");
        db.execute("DELETE FROM outbox_read_point");
    }

    /** Bloqueios de linha U/X mantidos por outra sessão durante a publicação de um lote, e linhas atualizadas. */
    private void rowLocksDuringPublication(RelayStrategyType strategy, StringBuilder report) {
        try (OutboxDb.Transaction transaction = db.open()) {
            for (int i = 0; i < LOCK_BATCH; i++) {
                transaction.insert(TestEnvelopes.of("agg-lock-" + i));
            }
            transaction.commit();
        }
        AtomicLong maxLocks = new AtomicLong();
        ScriptedPublisher publisher = new ScriptedPublisher()
                .before(message -> maxLocks.accumulateAndGet(db.count(ROW_LOCKS), Math::max));
        Relay relay = relayAs(strategy, publisher, "observacoes-bloqueios", "batch-size", String.valueOf(LOCK_BATCH));
        runUntil(relay, "lote publicado", () -> publisher.delivered().size() >= LOCK_BATCH);

        long updatedRows = db.count("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL");
        report.append("contenção: lote=").append(LOCK_BATCH)
                .append(", bloqueios de linha U/X mantidos durante a publicação (máximo)=").append(maxLocks.get())
                .append(", linhas atualizadas na outbox=").append(updatedRows).append('\n');
        db.execute("DELETE FROM outbox");
        db.execute("DELETE FROM outbox_read_point");
    }
}
