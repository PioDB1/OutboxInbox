package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InMemoryInboxDialect;
import br.com.db1.poc.inbox.support.ScriptedMessageSource;
import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Retry com backoff, DLQ e chaves bloqueadas, com relógio e {@link ScriptedMessageSource} simulados. */
class InboxRetryDlqTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static void awaitPending(ConsumerFixture fixture) {
        Await.until(TIMEOUT, "uma repetição agendada", () -> fixture.scheduler.pending() == 1);
    }

    private static void awaitDeadLetters(ConsumerFixture fixture, int expected) {
        Await.until(TIMEOUT, expected + " mensagens na DLQ", () -> fixture.source.deadLettered().size() >= expected);
    }

    private static InboxMessageHandler alwaysFails(AtomicInteger calls) {
        return (envelope, connection) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("falha sempre");
        };
    }

    /** TC-17: as tentativas seguintes ocorrem 100, 200, 300 e 300 ms depois da anterior. */
    @Test
    void intervalosDoBackoff() {
        List<Long> attemptTimes = Collections.synchronizedList(new ArrayList<Long>());
        ConsumerFixture[] holder = new ConsumerFixture[1];
        InboxMessageHandler handler = (envelope, connection) -> {
            attemptTimes.add(holder[0].scheduler.now());
            throw new IllegalStateException("falha sempre");
        };

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(5, 2), handler)) {
            holder[0] = fixture;
            fixture.deliver(TestEnvelopes.of("agregado-1"));
            for (int retry = 1; retry <= 4; retry++) {
                awaitPending(fixture);
                fixture.scheduler.runNext();
            }
            awaitDeadLetters(fixture, 1);

            assertEquals(Arrays.asList(0L, 100L, 300L, 600L, 900L), new ArrayList<>(attemptTimes));
            assertEquals(4.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
        }
    }

    /** TC-19 (simulado): a mensagem veneno vai para a DLQ depois de {@code max-attempts} tentativas. */
    @Test
    void mensagemVenenoVaiParaADlqAposOLimite() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();

        try (ConsumerFixture fixture =
                ConsumerFixture.inMemory(ConsumerFixture.config(3, 2), dialect, alwaysFails(calls))) {
            Envelope envelope = TestEnvelopes.of("agregado-1");
            fixture.deliver(envelope);
            awaitPending(fixture);
            fixture.scheduler.runNext();
            awaitPending(fixture);
            fixture.scheduler.runNext();
            awaitDeadLetters(fixture, 1);

            assertEquals(3, calls.get());
            ScriptedMessageSource.DeadLettered dead = fixture.source.deadLettered().get(0);
            assertEquals("max-attempts", dead.failure());
            assertEquals(3, dead.attempts());
            assertEquals(envelope.messageId().toString(), dead.delivery().headers().get("messageId"));
            assertEquals(0, fixture.source.acked().size());
            assertEquals(0, dialect.size());
            assertEquals(1.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
            assertEquals(2.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
        }
    }

    /** TC-6: a primeira tentativa desfaz efeito e inbox sem ack; a segunda aplica e confirma. */
    @Test
    void falhaDesfazEAProximaTentativaAplica() {
        H2Db db = H2Db.memory();
        AtomicInteger attempts = new AtomicInteger();
        InboxMessageHandler handler = (envelope, connection) -> {
            H2Db.insertEffect(connection, envelope.messageId(), "x");
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("primeira falha");
            }
        };

        try (ConsumerFixture fixture = ConsumerFixture.h2(db, ConsumerFixture.config(3, 2), handler)) {
            fixture.deliver(TestEnvelopes.of("agregado-1"));
            awaitPending(fixture);

            assertEquals(0, db.count("efeito"));
            assertEquals(0, db.count("inbox"));
            assertEquals(0, fixture.source.acked().size());

            fixture.scheduler.runNext();
            Await.until(TIMEOUT, "ack", () -> fixture.source.acked().size() == 1);

            assertEquals(1, db.count("efeito"));
            assertEquals(1, db.count("inbox"));
            assertEquals(1.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertEquals(0, fixture.source.deadLettered().size());
        }
    }

    /** TC-3: cada envelope inválido segue o retry até a DLQ sem chamar o tratador. */
    @Test
    void envelopeInvalidoSegueORetryAteADlq() {
        Envelope base = TestEnvelopes.of("agregado-1");
        SimulatedDelivery valid = SimulatedDelivery.of(base);
        List<SimulatedDelivery> invalid = Arrays.asList(
                valid.without("messageId"),
                valid.without("aggregateKey"),
                valid.with("messageId", "não-é-uuid"),
                valid.with("correlationId", "não-é-uuid"),
                valid.with("occurredAt", "ontem"),
                valid.with("schemaVersion", "abc"),
                valid.with("type", String.join("", Collections.nCopies(101, "x"))));

        for (SimulatedDelivery delivery : invalid) {
            AtomicInteger calls = new AtomicInteger();
            InMemoryInboxDialect dialect = new InMemoryInboxDialect();
            try (ConsumerFixture fixture =
                    ConsumerFixture.inMemory(ConsumerFixture.config(2, 2), dialect, (e, c) -> calls.incrementAndGet())) {
                fixture.source.deliver(delivery);
                awaitPending(fixture);
                fixture.scheduler.runNext();
                awaitDeadLetters(fixture, 1);

                assertEquals(0, calls.get());
                assertEquals(0, dialect.size());
                assertEquals("max-attempts", fixture.source.deadLettered().get(0).failure());
                assertEquals(2, fixture.source.deadLettered().get(0).attempts());
                assertEquals(1.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            }
        }
    }

    /** TC-7: conexão em autocommit é falha de processamento e a mensagem vai para a DLQ. */
    @Test
    void conexaoEmAutocommitVaiParaADlq() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        ConsumerFixtureAutocommit.run(dialect, calls);

        assertEquals(0, calls.get());
        assertEquals(0, dialect.size());
    }

    /** TC-18: outras chaves seguem durante o retry; a mesma chave espera a anterior. */
    @Test
    void outrasChavesSeguemEAMesmaChaveEspera() {
        List<String> applied = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger a1Attempts = new AtomicInteger();
        InboxMessageHandler handler = (envelope, connection) -> {
            String id = envelope.idempotencyKey();
            if (id.equals("A1") && a1Attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("A1 falha nas duas primeiras tentativas");
            }
            applied.add(id);
        };

        // uma só thread de trabalho: a espera do backoff não pode ocupá-la
        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(5, 1), handler)) {
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("A")).with("idempotencyKey", "A1"));
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("A")).with("idempotencyKey", "A2"));
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("B")).with("idempotencyKey", "B1"));

            Await.until(TIMEOUT, "B1 aplicada durante o backoff de A1", () -> applied.contains("B1"));
            awaitPending(fixture);
            assertEquals(Collections.singletonList("B1"), new ArrayList<>(applied));

            fixture.scheduler.runNext();
            awaitPending(fixture);
            assertEquals(Collections.singletonList("B1"), new ArrayList<>(applied));
            fixture.scheduler.runNext();
            Await.until(TIMEOUT, "A1 e A2 aplicadas", () -> applied.size() == 3);

            assertEquals(Arrays.asList("B1", "A1", "A2"), new ArrayList<>(applied));
        }
    }

    /** TC-20 (simulado): a mensagem veneno bloqueia a chave; as seguintes vão para a DLQ; outras chaves seguem. */
    @Test
    void chaveBloqueadaEnviaAsSeguintesParaADlq() {
        List<String> handled = Collections.synchronizedList(new ArrayList<String>());
        InboxMessageHandler handler = (envelope, connection) -> {
            handled.add(envelope.idempotencyKey());
            if (envelope.aggregateKey().equals("A")) {
                throw new IllegalStateException("veneno");
            }
        };
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 4), dialect, handler)) {
            for (String id : new String[] {"A1", "A2", "A3"}) {
                fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("A")).with("idempotencyKey", id));
            }
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("B")).with("idempotencyKey", "B1"));

            awaitDeadLetters(fixture, 3);
            Await.until(TIMEOUT, "B1 aplicada", () -> fixture.source.acked().size() == 1);

            List<ScriptedMessageSource.DeadLettered> dead = fixture.source.deadLettered();
            assertEquals("A1", dead.get(0).delivery().headers().get("idempotencyKey"));
            assertEquals("max-attempts", dead.get(0).failure());
            assertEquals(1, dead.get(0).attempts());
            for (int i = 1; i < 3; i++) {
                assertEquals("blocked-key", dead.get(i).failure());
                assertEquals(0, dead.get(i).attempts());
            }
            assertEquals("A2", dead.get(1).delivery().headers().get("idempotencyKey"));
            assertEquals("A3", dead.get(2).delivery().headers().get("idempotencyKey"));
            assertEquals(Arrays.asList("A1", "B1"), sorted(handled));
            assertEquals(1, dialect.size());
            assertEquals(3.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
        }
    }

    /** TC-21: falha na publicação da DLQ mantém a original sem ack e repete pelos intervalos do backoff. */
    @Test
    void falhaNaPublicacaoDaDlqRepeteSemContarTentativa() {
        AtomicInteger calls = new AtomicInteger();

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 2), alwaysFails(calls))) {
            fixture.source.failNextDeadLetters(2);
            fixture.deliver(TestEnvelopes.of("agregado-1"));

            awaitPending(fixture);
            assertEquals(100, fixture.scheduler.nextDelay());
            assertEquals(0, fixture.source.acked().size());
            fixture.scheduler.runNext();
            awaitPending(fixture);
            assertEquals(200, fixture.scheduler.nextDelay());
            assertEquals(0, fixture.source.deadLettered().size());
            fixture.scheduler.runNext();
            awaitDeadLetters(fixture, 1);

            assertEquals(3, fixture.source.deadLetterCalls());
            assertEquals(1, calls.get());
            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertEquals(1.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
        }
    }

    /** TC-24: a mensagem sem {@code aggregateKey} usa a chave reservada e bloqueia só ela. */
    @Test
    void semAggregateKeyUsaAChaveReservada() {
        AtomicInteger valid = new AtomicInteger();
        InboxMessageHandler handler = (envelope, connection) -> valid.incrementAndGet();

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 2), handler)) {
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("x")).without("aggregateKey"));
            awaitDeadLetters(fixture, 1);
            fixture.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("x")).without("aggregateKey"));
            awaitDeadLetters(fixture, 2);
            fixture.deliver(TestEnvelopes.of("outra-chave"));
            Await.until(TIMEOUT, "mensagem válida aplicada", () -> fixture.source.acked().size() == 1);

            assertEquals("max-attempts", fixture.source.deadLettered().get(0).failure());
            assertEquals("blocked-key", fixture.source.deadLettered().get(1).failure());
            assertEquals(0, fixture.source.deadLettered().get(1).attempts());
            assertEquals(1, valid.get());
            assertTrue(fixture.blockedKeys.isBlocked(InboxConsumer.RESERVED_KEY));
            assertFalse(fixture.blockedKeys.isBlocked("outra-chave"));
        }
    }

    /** TC-25: o reinício libera as chaves bloqueadas (limite registrado na P7). */
    @Test
    void reinicioLiberaAsChavesBloqueadas() {
        List<String> handled = Collections.synchronizedList(new ArrayList<String>());
        InboxMessageHandler poisonOnA1 = (envelope, connection) -> {
            handled.add(envelope.idempotencyKey());
            if (envelope.idempotencyKey().equals("A1")) {
                throw new IllegalStateException("veneno");
            }
        };
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        try (ConsumerFixture first = ConsumerFixture.inMemory(ConsumerFixture.config(1, 2), dialect, poisonOnA1)) {
            first.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("A")).with("idempotencyKey", "A1"));
            awaitDeadLetters(first, 1);
            Await.until(TIMEOUT, "chave liberada", () -> first.consumer.activeKeys() == 0);
            assertTrue(first.blockedKeys.isBlocked("A"));
        }

        try (ConsumerFixture second = ConsumerFixture.inMemory(ConsumerFixture.config(1, 2), dialect, poisonOnA1)) {
            second.source.deliver(SimulatedDelivery.of(TestEnvelopes.of("A")).with("idempotencyKey", "A4"));
            Await.until(TIMEOUT, "A4 aplicada", () -> second.source.acked().size() == 1);

            assertEquals(Arrays.asList("A1", "A4"), new ArrayList<>(handled));
            assertEquals(0, second.source.deadLettered().size());
        }
    }

    /** Entrega perdida na publicação da DLQ: a chave é liberada, sem repetir e sem bloquear. */
    @Test
    void entregaPerdidaNaDlqLiberaAChaveSemBloquear() {
        AtomicInteger calls = new AtomicInteger();

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 2), alwaysFails(calls))) {
            fixture.source.markStale();
            fixture.deliver(TestEnvelopes.of("agregado-1"));

            Await.until(TIMEOUT, "chave liberada", () -> fixture.consumer.activeKeys() == 0);

            assertEquals(1, calls.get());
            assertEquals(0, fixture.scheduler.pending());
            assertEquals(0.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
            assertFalse(fixture.blockedKeys.isBlocked("agregado-1"));
            assertTrue(fixture.source.events().contains("dlq-stale"));
        }
    }

    /** Entrega perdida no ack: o aviso é registrado, a chave é liberada e nada é repetido. */
    @Test
    void entregaPerdidaNoAckLiberaAChave() {
        AtomicInteger calls = new AtomicInteger();

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2),
                (e, c) -> calls.incrementAndGet())) {
            fixture.source.markStale();
            fixture.deliver(TestEnvelopes.of("agregado-1"));

            Await.until(TIMEOUT, "chave liberada", () -> fixture.consumer.activeKeys() == 0);

            assertEquals(1, calls.get());
            assertEquals(0, fixture.scheduler.pending());
            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertTrue(fixture.source.events().contains("ack-stale"));
        }
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    /** Consumidor sobre uma inbox cujas conexões estão em autocommit. */
    private static final class ConsumerFixtureAutocommit {

        private ConsumerFixtureAutocommit() {
        }

        static void run(InMemoryInboxDialect dialect, AtomicInteger calls) {
            ScriptedMessageSource source = new ScriptedMessageSource();
            ManualScheduler scheduler = new ManualScheduler();
            io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try (InboxConsumer consumer = new InboxConsumer(ConsumerFixture.config(1, 2), source, dialect,
                    dialect.transactions(true), (e, c) -> calls.incrementAndGet(), new InMemoryBlockedKeys(),
                    new InboxMetrics(registry), scheduler)) {
                consumer.start();
                source.deliver(SimulatedDelivery.of(TestEnvelopes.of("agregado-1")));
                Await.until(TIMEOUT, "mensagem na DLQ", () -> source.deadLettered().size() == 1);

                assertEquals("max-attempts", source.deadLettered().get(0).failure());
                assertEquals(1, source.deadLettered().get(0).attempts());
            }
        }
    }
}
