package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Retry com backoff e acúmulo com o broker indisponível, nas duas estratégias (TC-29 a TC-34). */
class RelayBackoffIT extends RelayIT {

    private static final String[] BACKOFF_2S = {"backoff.initial", "200ms", "backoff.multiplier", "2.0",
            "backoff.max", "2s"};

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

    private static List<Long> intervalsMillis(ScriptedPublisher publisher) {
        List<ScriptedPublisher.Attempt> attempts = publisher.attempts();
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < attempts.size(); i++) {
            intervals.add(Duration.ofNanos(attempts.get(i).nanoTime() - attempts.get(i - 1).nanoTime()).toMillis());
        }
        return intervals;
    }

    /** TC-29: o broker indisponível acumula as linhas e, na volta, todas são publicadas sem perda. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void brokerIndisponivelAcumulaEPublicaTudoNaVolta(RelayStrategyType strategy) throws IOException {
        broker.proxy().disable();
        List<Envelope> envelopes = insert(50);
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        Relay relay = relay(strategy, publisher, "backoff.initial", "100ms", "backoff.max", "1s");
        relay.start();

        Await.until(TIMEOUT, "tentativas durante a indisponibilidade", () -> publisher.attempts().size() >= 3);
        assertEquals(50, db.outboxCount());
        assertEquals(50, db.pendingCount(strategy));

        broker.proxy().enable();
        Await.until(Duration.ofSeconds(60), "todas as linhas enviadas", () -> db.pendingCount(strategy) == 0);

        Set<UUID> expected = new HashSet<>();
        envelopes.forEach(e -> expected.add(e.messageId()));
        Set<UUID> inQueue = new HashSet<>();
        Await.until(TIMEOUT, "50 mensagens distintas na fila", () -> {
            readAvailable().forEach(r -> inQueue.add(UUID.fromString(r.header("messageId"))));
            return inQueue.size() >= expected.size();
        });
        assertEquals(expected, inQueue);
        assertEquals(50, db.outboxCount());
    }

    /** TC-30: os intervalos entre tentativas são não decrescentes e limitados pelo teto. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void intervalosCrescemAteOTeto(RelayStrategyType strategy) throws IOException {
        broker.proxy().disable();
        db.insert(TestEnvelopes.of("agg-1"));
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        Relay relay = relay(strategy, publisher, BACKOFF_2S);
        relay.start();

        Await.until(Duration.ofSeconds(40), "sete tentativas", () -> publisher.attempts().size() >= 7);

        List<Long> intervals = intervalsMillis(publisher);
        for (int i = 0; i < intervals.size(); i++) {
            assertTrue(intervals.get(i) <= 2_000 + 300, "intervalo " + i + " excede o teto: " + intervals);
            if (i > 0) {
                assertTrue(intervals.get(i) >= intervals.get(i - 1) - 150, "intervalo decresceu: " + intervals);
            }
        }
        assertTrue(intervals.get(0) >= 150, "primeiro intervalo menor que o inicial: " + intervals);
    }

    /** TC-31: o backoff evita tentativas em laço apertado numa janela fixa. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void backoffEvitaTentativasEmLacoApertado(RelayStrategyType strategy) throws IOException {
        broker.proxy().disable();
        db.insert(TestEnvelopes.of("agg-1"));
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        String[] overrides = {"backoff.initial", "200ms", "backoff.multiplier", "2.0", "backoff.max", "2s",
                "poll-interval", "500ms"};
        Relay relay = relay(strategy, publisher, overrides);
        relay.start();

        long windowStart = System.nanoTime();
        Await.until(Duration.ofSeconds(20), "janela de 10 s", () ->
                System.nanoTime() - windowStart >= Duration.ofSeconds(10).toNanos());

        long cyclesWithoutBackoff = 10_000 / 500;
        assertTrue(publisher.attempts().size() < cyclesWithoutBackoff,
                "tentativas na janela: " + publisher.attempts().size());
    }

    /** TC-32: a linha pendente é publicada logo que o backoff vigente termina. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void linhaPendenteEPublicadaLogoQueOBackoffTermina(RelayStrategyType strategy) throws IOException {
        broker.proxy().disable();
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        Relay relay = relay(strategy, publisher, BACKOFF_2S);
        relay.start();
        Await.until(TIMEOUT, "três tentativas", () -> publisher.attempts().size() >= 3);

        long restoredAt = System.nanoTime();
        broker.proxy().enable();
        awaitMessages(1, Duration.ofSeconds(10));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - restoredAt);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(2).plusSeconds(1)) <= 0, "demorou " + elapsed);
    }

    /** TC-33: o backoff é zerado depois de um sucesso. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void backoffEZeradoDepoisDeUmSucesso(RelayStrategyType strategy) {
        Envelope first = TestEnvelopes.of("agg-1");
        db.insert(first);
        AtomicInteger firstFailures = new AtomicInteger(2);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m ->
                m.messageId().equals(first.messageId()) && firstFailures.getAndDecrement() > 0);
        Relay relay = relay(strategy, publisher, BACKOFF_2S);
        runUntil(relay, "primeira linha publicada", () -> publisher.delivered().size() == 1);

        Envelope second = TestEnvelopes.of("agg-1");
        db.insert(second);
        AtomicInteger secondFailures = new AtomicInteger(1);
        publisher.failWhen(m -> m.messageId().equals(second.messageId()) && secondFailures.getAndDecrement() > 0);
        runUntil(relay, "segunda linha publicada", () -> publisher.delivered().size() == 2);

        List<ScriptedPublisher.Attempt> attempts = publisher.attemptsOf(second.messageId());
        assertEquals(2, attempts.size());
        long interval = Duration.ofNanos(attempts.get(1).nanoTime() - attempts.get(0).nanoTime()).toMillis();
        assertTrue(interval >= 190 && interval < 400, "intervalo antes da nova tentativa: " + interval + " ms");
    }

    static Stream<Arguments> toxics() {
        return Stream.of(RelayStrategyType.values()).flatMap(strategy ->
                Stream.of("latency", "timeout", "reset_peer").map(toxic -> Arguments.of(strategy, toxic)));
    }

    /** TC-34: rede instável segue o backoff e, removido o toxic, publica tudo sem perda. */
    @ParameterizedTest
    @MethodSource("toxics")
    void redeInstavelSegueOBackoffENaoPerdeMensagens(RelayStrategyType strategy, String toxic) throws IOException {
        Toxic applied;
        switch (toxic) {
            case "latency":
                applied = broker.proxy().toxics().latency("instavel", ToxicDirection.DOWNSTREAM, 5_000);
                break;
            case "timeout":
                applied = broker.proxy().toxics().timeout("instavel", ToxicDirection.DOWNSTREAM, 0);
                break;
            default:
                applied = broker.proxy().toxics().resetPeer("instavel", ToxicDirection.DOWNSTREAM, 0);
                break;
        }
        List<Envelope> envelopes = insert(5);
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        Relay relay = relay(strategy, publisher, "backoff.initial", "100ms", "backoff.max", "1s");
        relay.start();
        Await.until(Duration.ofSeconds(40), "duas falhas durante o toxic",
                () -> publisher.attempts().stream().filter(a -> !a.succeeded()).count() >= 2);
        assertEquals(5, db.pendingCount(strategy));

        applied.remove();
        Await.until(Duration.ofSeconds(60), "todas as linhas enviadas", () -> db.pendingCount(strategy) == 0);

        Set<UUID> expected = new HashSet<>();
        envelopes.forEach(e -> expected.add(e.messageId()));
        Set<UUID> inQueue = new HashSet<>();
        Await.until(TIMEOUT, "mensagens na fila", () -> {
            readAvailable().forEach(r -> inQueue.add(UUID.fromString(r.header("messageId"))));
            return inQueue.containsAll(expected);
        });
        assertEquals(5, db.outboxCount());
    }
}
