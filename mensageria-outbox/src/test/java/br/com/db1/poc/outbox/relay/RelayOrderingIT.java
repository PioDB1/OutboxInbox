package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Ordem por {@code aggregateKey} e nenhuma linha pulada, nas duas estratégias (TC-18 a TC-22). */
class RelayOrderingIT extends RelayIT {

    private static List<UUID> ids(List<Received> received) {
        return received.stream().map(r -> UUID.fromString(r.header("messageId"))).toList();
    }

    private static List<UUID> ids(ScriptedPublisher publisher) {
        return publisher.delivered().stream().map(m -> m.messageId()).toList();
    }

    /** TC-18: duas mensagens do mesmo {@code aggregateKey} saem na ordem em que ocorreram. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void mesmoAggregateKeySaiNaOrdem(RelayStrategyType strategy) {
        Envelope older = TestEnvelopes.of("agg-1");
        Envelope newer = TestEnvelopes.of("agg-1");
        db.insert(older);
        db.insert(newer);
        Relay relay = relay(strategy, rabbitPublisher());

        runUntil(relay, "duas linhas enviadas", () -> db.pendingCount(strategy) == 0);

        assertEquals(List.of(older.messageId(), newer.messageId()), ids(awaitMessages(2, Duration.ofSeconds(10))));
    }

    /** TC-19: a falha de uma chave retém as posteriores da mesma chave; as demais chaves seguem. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void falhaDeUmaChaveNaoLiberaAPosteriorDaMesmaChave(RelayStrategyType strategy) {
        Envelope x1 = TestEnvelopes.of("X");
        Envelope y1 = TestEnvelopes.of("Y");
        Envelope x2 = TestEnvelopes.of("X");
        db.insert(x1);
        db.insert(y1);
        db.insert(x2);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> m.messageId().equals(x1.messageId()));
        Relay relay = relay(strategy, publisher);

        relay.runOnce();

        assertEquals(List.of(y1.messageId()), ids(publisher));
        assertFalse(db.isSent(strategy, x1.messageId()));
        assertFalse(db.isSent(strategy, x2.messageId()));

        publisher.stopFailing();
        runUntil(relay, "linhas de X enviadas", () -> publisher.delivered().size() == 3);

        assertEquals(List.of(y1.messageId(), x1.messageId(), x2.messageId()), ids(publisher));
    }

    /** TC-20: commit fora da ordem de {@code seq} em chaves diferentes não pula a linha da transação anterior. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void commitForaDeOrdemEmChavesDiferentesNaoPulaLinha(RelayStrategyType strategy) {
        Envelope fromT1 = TestEnvelopes.of("agg-t1");
        Envelope fromT2 = TestEnvelopes.of("agg-t2");
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher);

        try (OutboxDb.Transaction t1 = db.open()) {
            t1.insert(fromT1);
            db.insert(fromT2);
            relay.runOnce();
            relay.runOnce();
            relay.runOnce();
            t1.commit();
        }
        runUntil(relay, "duas linhas enviadas", () -> db.pendingCount(strategy) == 0);

        assertEquals(2, publisher.delivered().size());
        assertTrue(ids(publisher).containsAll(List.of(fromT1.messageId(), fromT2.messageId())));
    }

    /** TC-21: commit fora da ordem de {@code seq} no mesmo {@code aggregateKey} preserva a ordem. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void commitForaDeOrdemNoMesmoAggregateKeyPreservaAOrdem(RelayStrategyType strategy) {
        Envelope fromT1 = TestEnvelopes.of("X");
        Envelope fromT2 = TestEnvelopes.of("X");
        Relay relay = relay(strategy, rabbitPublisher());

        try (OutboxDb.Transaction t1 = db.open()) {
            t1.insert(fromT1);
            db.insert(fromT2);
            relay.runOnce();
            relay.runOnce();
            assertEquals(List.of(), readAvailable(), "nada de X é publicado com T1 aberta");
            t1.commit();
        }
        runUntil(relay, "duas linhas enviadas", () -> db.pendingCount(strategy) == 0);

        assertEquals(List.of(fromT1.messageId(), fromT2.messageId()), ids(awaitMessages(2, Duration.ofSeconds(10))));
    }

    /** TC-22: a transação revertida deixa de bloquear o {@code aggregateKey} (claim). */
    @Test
    void transacaoRevertidaDeixaDeBloquearOAggregateKey() {
        RelayStrategyType strategy = RelayStrategyType.CLAIM;
        Envelope fromT1 = TestEnvelopes.of("X");
        Envelope fromT2 = TestEnvelopes.of("X");
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher);

        try (OutboxDb.Transaction t1 = db.open()) {
            t1.insert(fromT1);
            db.insert(fromT2);
            relay.runOnce();
            assertEquals(List.of(), ids(publisher));
            t1.rollback();
        }
        runUntil(relay, "linha de T2 enviada", () -> db.isSent(strategy, fromT2.messageId()));

        assertEquals(List.of(fromT2.messageId()), ids(publisher));
    }
}
