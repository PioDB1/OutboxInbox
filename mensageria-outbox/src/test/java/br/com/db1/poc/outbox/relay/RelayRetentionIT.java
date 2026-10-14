package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.retention.OutboxRetention;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Retenção da outbox nas duas estratégias (TC-44 a TC-48). */
class RelayRetentionIT extends RelayIT {

    private static final String[] SHORT_RETENTION = {"retention.max-age", "2s", "retention.interval", "200ms"};

    /** Dialeto que repassa ao real e conta as chamadas de remoção, com o tamanho de cada remoção. */
    private static OutboxDialect spy(List<Integer> deletions) {
        OutboxDialect real = new SqlServerOutboxDialect();
        return (OutboxDialect) Proxy.newProxyInstance(OutboxDialect.class.getClassLoader(),
                new Class<?>[] {OutboxDialect.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(real, args);
                        if (method.getName().startsWith("delete")) {
                            deletions.add((Integer) result);
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Relay relayWith(RelayStrategyType strategy, OutboxDialect dialect, MessagePublisher publisher,
            String holderId, String... overrides) {
        RelayConfig config = RelayConfig.fromProperties(properties(strategy, overrides));
        Relay relay = switch (strategy) {
            case CLAIM -> new ClaimRelay(config, dataSource, dialect, publisher, meters, holderId);
            case READ_POINT -> new ReadPointRelay(config, dataSource, dialect, publisher, meters, holderId);
        };
        return closeAtEnd(relay);
    }

    private void insertAll(int count) {
        try (OutboxDb.Transaction transaction = db.open()) {
            for (int i = 0; i < count; i++) {
                transaction.insert(TestEnvelopes.of("agg-" + (i % 10)));
            }
            transaction.commit();
        }
    }

    /** TC-44: a limpeza remove somente as linhas enviadas além da idade máxima. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void limpezaRemoveSomenteLinhasEnviadasVencidas(RelayStrategyType strategy) {
        insertAll(3);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, SHORT_RETENTION);
        runUntil(relay, "três linhas enviadas", () -> publisher.delivered().size() == 3);

        runUntil(relay, "linhas enviadas removidas", () -> db.outboxCount() == 0);

        Envelope recent = TestEnvelopes.of("agg-recent");
        db.insert(recent);
        runUntil(relay, "linha recente enviada", () -> db.pendingCount(strategy) == 0);
        assertTrue(db.exists(recent.messageId()), "a linha recente ainda não venceu");
    }

    /** TC-45: a limpeza nunca remove linha pendente. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void limpezaNuncaRemoveLinhaPendente(RelayStrategyType strategy) {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        ScriptedPublisher publisher = new ScriptedPublisher().failAll();
        Relay relay = relay(strategy, publisher, "retention.max-age", "1s", "retention.interval", "200ms",
                "backoff.initial", "100ms", "backoff.max", "200ms");

        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            relay.runOnce();
            assertTrue(db.exists(envelope.messageId()), "a linha pendente foi removida");
            sleep(100);
        }
        assertEquals(1, db.pendingCount(strategy));
    }

    /** TC-46: a limpeza preserva as linhas acima do ponto e o relay não pula mensagens. */
    @Test
    void limpezaDoPontoDeLeituraPreservaLinhasAcimaDoPonto() {
        RelayStrategyType strategy = RelayStrategyType.READ_POINT;
        insertAll(3);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, "retention.max-age", "1s", "retention.interval", "200ms");
        runUntil(relay, "três linhas enviadas e removidas", () -> publisher.delivered().size() == 3
                && db.outboxCount() == 0);

        Envelope fromT1 = TestEnvelopes.of("agg-t1");
        Envelope later = TestEnvelopes.of("agg-later");
        try (OutboxDb.Transaction t1 = db.open()) {
            t1.insert(fromT1);
            db.insert(later);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                relay.runOnce();
                assertTrue(db.exists(later.messageId()), "a linha acima do ponto foi removida");
                sleep(100);
            }
            t1.commit();
        }
        runUntil(relay, "as duas linhas publicadas", () -> {
            Set<UUID> ids = new HashSet<>();
            publisher.delivered().forEach(m -> ids.add(m.messageId()));
            return ids.contains(fromT1.messageId()) && ids.contains(later.messageId());
        });
    }

    /** TC-47: a limpeza remove em lotes de {@code retention.batch-size}. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void limpezaRemoveEmLotes(RelayStrategyType strategy) throws java.sql.SQLException {
        insertAll(35);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher);
        runUntil(relay, "35 linhas enviadas", () -> publisher.delivered().size() == 35);
        Await.until(TIMEOUT, "35 linhas vencidas", () ->
                db.count("SELECT COUNT(*) FROM outbox WHERE created_at < DATEADD(SECOND, -1, SYSUTCDATETIME())")
                        == 35);
        List<Integer> deletions = Collections.synchronizedList(new ArrayList<>());
        OutboxRetention retention = new OutboxRetention(spy(deletions), dataSource, strategy,
                Duration.ofSeconds(1), Duration.ofSeconds(60), 10);

        int removed = retention.runNow(() -> true);

        assertEquals(35, removed);
        assertEquals(List.of(10, 10, 10, 5), deletions);
        assertEquals(0, db.outboxCount());
    }

    /** TC-48: somente o relay detentor do lease executa a limpeza. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void somenteORelayAtivoExecutaALimpeza(RelayStrategyType strategy) {
        insertAll(5);
        List<Integer> deletionsA = Collections.synchronizedList(new ArrayList<>());
        List<Integer> deletionsB = Collections.synchronizedList(new ArrayList<>());
        ScriptedPublisher publisherA = new ScriptedPublisher();
        ScriptedPublisher publisherB = new ScriptedPublisher();
        Relay a = relayWith(strategy, spy(deletionsA), publisherA, "relay-a", "retention.max-age", "1s",
                "retention.interval", "200ms");
        Relay b = relayWith(strategy, spy(deletionsB), publisherB, "relay-b", "retention.max-age", "1s",
                "retention.interval", "200ms");
        a.start();
        b.start();

        Await.until(Duration.ofSeconds(40), "linhas enviadas e removidas", () -> db.outboxCount() == 0);

        String holder = db.leaseHolder();
        List<Integer> holderDeletions = holder.equals("relay-a") ? deletionsA : deletionsB;
        List<Integer> otherDeletions = holder.equals("relay-a") ? deletionsB : deletionsA;
        assertTrue(!holderDeletions.isEmpty(), "o detentor deve executar a limpeza");
        assertEquals(List.of(), otherDeletions);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
