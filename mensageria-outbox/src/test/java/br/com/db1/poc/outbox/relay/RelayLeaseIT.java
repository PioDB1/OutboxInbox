package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Lease de relay único, relay habilitado por instância e reinício, nas duas estratégias (TC-36 a TC-39, TC-41 a TC-43). */
class RelayLeaseIT extends RelayIT {

    private static final String[] SHORT_LEASE = {"lease.duration", "1500ms", "lease.renew-interval", "500ms"};

    private static Set<UUID> ids(List<Envelope> envelopes) {
        Set<UUID> ids = new HashSet<>();
        envelopes.forEach(e -> ids.add(e.messageId()));
        return ids;
    }

    private List<Envelope> insert(int count) {
        List<Envelope> envelopes = new ArrayList<>();
        try (OutboxDb.Transaction transaction = db.open()) {
            for (int i = 0; i < count; i++) {
                Envelope envelope = TestEnvelopes.of("agg-" + (i % 10));
                transaction.insert(envelope);
                envelopes.add(envelope);
            }
            transaction.commit();
        }
        return envelopes;
    }

    /** TC-36: somente o detentor do lease publica. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void somenteODetentorDoLeasePublica(RelayStrategyType strategy) {
        ScriptedPublisher publisherA = new ScriptedPublisher();
        ScriptedPublisher publisherB = new ScriptedPublisher();
        Relay a = relayAs(strategy, publisherA, "relay-a");
        Relay b = relayAs(strategy, publisherB, "relay-b");
        a.start();
        b.start();
        insert(3);

        Await.until(TIMEOUT, "linhas enviadas", () -> db.pendingCount(strategy) == 0);

        assertEquals(1, db.count("SELECT COUNT(*) FROM outbox_lease"));
        String holder = db.leaseHolder();
        assertTrue(holder.equals("relay-a") || holder.equals("relay-b"), holder);
        ScriptedPublisher holderPublisher = holder.equals("relay-a") ? publisherA : publisherB;
        ScriptedPublisher other = holder.equals("relay-a") ? publisherB : publisherA;
        assertEquals(3, holderPublisher.delivered().size());
        assertEquals(0, other.attempts().size());
    }

    /** TC-37: outro relay assume o lease depois da expiração e publica as linhas pendentes. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void outroRelayAssumeDepoisDaExpiracao(RelayStrategyType strategy) {
        CountDownLatch frozen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedPublisher publisherA = new ScriptedPublisher().before(m -> {
            frozen.countDown();
            awaitQuietly(release);
        });
        ScriptedPublisher publisherB = new ScriptedPublisher();
        Relay a = relayAs(strategy, publisherA, "relay-a", SHORT_LEASE);
        Relay b = relayAs(strategy, publisherB, "relay-b", SHORT_LEASE);
        try {
            insert(1);
            a.start();
            awaitLatch(frozen);
            assertEquals("relay-a", db.leaseHolder());

            List<Envelope> later = insert(2);
            b.start();

            Await.until(TIMEOUT, "relay-b detém o lease", () -> "relay-b".equals(db.leaseHolder()));
            Set<UUID> laterIds = ids(later);
            Await.until(TIMEOUT, "relay-b publicou as linhas novas", () -> {
                Set<UUID> published = new HashSet<>();
                publisherB.delivered().forEach(m -> published.add(m.messageId()));
                return published.containsAll(laterIds);
            });
        } finally {
            release.countDown();
        }
    }

    /** TC-38: dois relays ativos por sobreposição forçada não perdem mensagem; as repetições têm o mesmo messageId. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void sobreposicaoForcadaNaoPerdeMensagem(RelayStrategyType strategy) {
        CountDownLatch frozen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        ScriptedPublisher publisherA = new ScriptedPublisher().before(m -> {
            if (firstCall.compareAndSet(true, false)) {
                frozen.countDown();
                awaitQuietly(release);
            }
        });
        ScriptedPublisher publisherB = new ScriptedPublisher();
        Relay a = relayAs(strategy, publisherA, "relay-a", SHORT_LEASE);
        Relay b = relayAs(strategy, publisherB, "relay-b", SHORT_LEASE);
        try {
            List<Envelope> envelopes = insert(5);
            a.start();
            awaitLatch(frozen);
            b.start();
            Await.until(TIMEOUT, "relay-b detém o lease", () -> "relay-b".equals(db.leaseHolder()));
            release.countDown();

            Await.until(TIMEOUT, "todas as linhas enviadas", () -> db.pendingCount(strategy) == 0);

            Set<UUID> published = new HashSet<>();
            publisherA.delivered().forEach(m -> published.add(m.messageId()));
            publisherB.delivered().forEach(m -> published.add(m.messageId()));
            assertEquals(ids(envelopes), published);
        } finally {
            release.countDown();
        }
    }

    /** TC-39: o relay desabilitado não adquire lease nem publica. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void relayDesabilitadoNaoAdquireLeaseNemPublica(RelayStrategyType strategy) {
        insert(1);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relayAs(strategy, publisher, "relay-off", "enabled", "false");

        relay.start();
        assertEquals(0, relay.runOnce());

        assertNull(db.leaseHolder());
        assertEquals(0, db.count("SELECT COUNT(*) FROM outbox_lease"));
        assertEquals(1, db.pendingCount(strategy));
        assertEquals(0, publisher.attempts().size());
    }

    /** TC-41: o reinício no meio de um lote conclui a publicação sem perda e sem intervenção manual. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void reinicioNoMeioDeUmLoteNaoPerdeLinhas(RelayStrategyType strategy) {
        List<Envelope> envelopes = insert(250);
        CountDownLatch midBatch = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        ScriptedPublisher publisherA = new ScriptedPublisher().after(m -> {
            if (delivered.incrementAndGet() == 120) {
                midBatch.countDown();
                awaitQuietly(never);
            }
        });
        Relay a = relayAs(strategy, publisherA, "relay-a");
        a.start();
        awaitLatch(midBatch);
        a.close();

        ScriptedPublisher publisherB = new ScriptedPublisher();
        Relay b = relayAs(strategy, publisherB, "relay-b");
        runUntil(b, "todas as linhas enviadas", () -> db.pendingCount(strategy) == 0);

        Set<UUID> published = new HashSet<>();
        publisherA.delivered().forEach(m -> published.add(m.messageId()));
        publisherB.delivered().forEach(m -> published.add(m.messageId()));
        assertEquals(ids(envelopes), published);
    }

    /** TC-42: {@code close()} libera o lease antes da validade. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void closeLiberaOLease(RelayStrategyType strategy) {
        Relay a = relayAs(strategy, new ScriptedPublisher(), "relay-a");
        a.start();
        Await.until(TIMEOUT, "relay-a detém o lease", () -> "relay-a".equals(db.leaseHolder()));

        a.close();

        Relay b = relayAs(strategy, new ScriptedPublisher(), "relay-b");
        b.start();
        Await.until(Duration.ofSeconds(5), "relay-b detém o lease", () -> "relay-b".equals(db.leaseHolder()));
    }

    /** TC-43: o detentor renova o lease sem mudar de {@code holder_id}. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void detentorRenovaOLease(RelayStrategyType strategy) {
        Relay a = relayAs(strategy, new ScriptedPublisher(), "relay-a", "lease.duration", "3s",
                "lease.renew-interval", "1s");
        a.start();
        Await.until(TIMEOUT, "relay-a detém o lease", () -> "relay-a".equals(db.leaseHolder()));
        long first = db.leaseExpiresAtMillis();

        Await.until(Duration.ofSeconds(10), "expires_at avançou", () -> db.leaseExpiresAtMillis() > first);

        assertEquals("relay-a", db.leaseHolder());
        assertNotEquals(first, db.leaseExpiresAtMillis());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.getSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("A thread do relay não chegou ao ponto esperado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Bloqueia a thread do relay até o latch; uma interrupção (close) vira exceção que encerra o ciclo. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido", e);
        }
    }
}
