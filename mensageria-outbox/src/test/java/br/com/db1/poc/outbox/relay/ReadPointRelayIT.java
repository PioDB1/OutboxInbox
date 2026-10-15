package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Seleção de estratégia e comportamento próprio da estratégia de ponto de leitura (TC-23, TC-26 a TC-28). */
class ReadPointRelayIT extends RelayIT {

    private static final byte[] ZERO = new byte[8];

    /** TC-23: com {@code claim} marca {@code sent_at}; com {@code read-point} avança {@code last_rv}. */
    @Test
    void estrategiaClaimMarcaSentAtEMantemOPontoZerado() {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        Relay relay = relay(RelayStrategyType.CLAIM, new ScriptedPublisher());

        assertInstanceOf(ClaimRelay.class, relay);
        assertEquals(1, relay.runOnce());

        assertEquals(1, db.count("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL"));
        assertArrayEquals(ZERO, db.readPoint());
    }

    /** TC-23: com {@code read-point} a linha fica sem {@code sent_at} e o ponto avança até o {@code rv} dela. */
    @Test
    void estrategiaPontoDeLeituraAvancaOPontoESemMarcarSentAt() {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        Relay relay = relay(RelayStrategyType.READ_POINT, new ScriptedPublisher());

        assertInstanceOf(ReadPointRelay.class, relay);
        assertEquals(1, relay.runOnce());

        assertEquals(0, db.count("SELECT COUNT(*) FROM outbox WHERE sent_at IS NOT NULL"));
        assertArrayEquals(db.rvOf(envelope.messageId()), db.readPoint());
    }

    /** TC-26: o ponto avança até o prefixo contíguo publicado; a linha com falha fica pendente. */
    @Test
    void pontoAvancaAteOPrefixoContiguoPublicado() {
        Envelope r1 = TestEnvelopes.of("a");
        Envelope r2 = TestEnvelopes.of("b");
        Envelope r3 = TestEnvelopes.of("c");
        db.insert(r1);
        db.insert(r2);
        db.insert(r3);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> m.messageId().equals(r2.messageId()));
        Relay relay = relay(RelayStrategyType.READ_POINT, publisher);

        relay.runOnce();

        assertEquals(Arrays.asList(r1.messageId(), r3.messageId()), publisher.delivered().stream().map(m -> m.messageId()).collect(Collectors.toList()));
        assertArrayEquals(db.rvOf(r1.messageId()), db.readPoint());
        assertFalse(db.isSent(RelayStrategyType.READ_POINT, r2.messageId()));
    }

    /** TC-26: depois que a linha com falha é publicada, o ponto salta sobre as linhas já publicadas à frente. */
    @Test
    void pontoSaltaSobreLinhasPublicadasAFrenteQuandoAFalhaSeResolve() {
        Envelope r1 = TestEnvelopes.of("a");
        Envelope r2 = TestEnvelopes.of("b");
        Envelope r3 = TestEnvelopes.of("c");
        db.insert(r1);
        db.insert(r2);
        db.insert(r3);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> m.messageId().equals(r2.messageId()));
        Relay relay = relay(RelayStrategyType.READ_POINT, publisher);
        relay.runOnce();

        publisher.stopFailing();
        runUntil(relay, "ponto no fim", () -> Arrays.equals(db.rvOf(r3.messageId()), db.readPoint()));

        assertEquals(Arrays.asList(r1.messageId(), r3.messageId(), r2.messageId()),
                publisher.delivered().stream().map(m -> m.messageId()).collect(Collectors.toList()));
    }

    /** TC-27: um relay novo retoma a leitura a partir do ponto gravado no banco. */
    @Test
    void relayNovoRetomaAPartirDoPontoGravado() {
        Envelope r1 = TestEnvelopes.of("a");
        Envelope r2 = TestEnvelopes.of("b");
        db.insert(r1);
        db.insert(r2);
        ScriptedPublisher firstPublisher = new ScriptedPublisher();
        Relay first = relayAs(RelayStrategyType.READ_POINT, firstPublisher, "relay-a");
        assertEquals(2, first.runOnce());
        first.close();

        Envelope r3 = TestEnvelopes.of("c");
        db.insert(r3);
        ScriptedPublisher secondPublisher = new ScriptedPublisher();
        Relay second = relayAs(RelayStrategyType.READ_POINT, secondPublisher, "relay-b");

        assertEquals(1, second.runOnce());

        assertEquals(Arrays.asList(r3.messageId()), secondPublisher.delivered().stream().map(m -> m.messageId()).collect(Collectors.toList()));
        assertArrayEquals(db.rvOf(r3.messageId()), db.readPoint());
    }

    /** TC-28: a linha publicada mantém {@code sent_at} nulo e fica com {@code rv <= last_rv}. */
    @Test
    void estrategiaSoInsereNaOutbox() throws SQLException {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        UUID id = envelope.messageId();
        byte[] rvBefore = db.rvOf(id);
        Relay relay = relay(RelayStrategyType.READ_POINT, new ScriptedPublisher());

        relay.runOnce();

        assertEquals(1, db.count("SELECT COUNT(*) FROM outbox WHERE message_id = ? AND sent_at IS NULL", id.toString()));
        assertArrayEquals(rvBefore, db.rvOf(id));
        assertTrue(db.isSent(RelayStrategyType.READ_POINT, id));
        assertEquals(0, db.pendingCount(RelayStrategyType.READ_POINT));
        assertEquals(0, PendingQuery.create(new SqlServerOutboxDialect()).pending(dataSource, RelayStrategyType.READ_POINT).count());
    }
}
