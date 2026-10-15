package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.rabbit.RabbitTopology;
import br.com.db1.poc.outbox.support.RelayIT;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Publicação com confirm nas duas estratégias (TC-4, TC-5, TC-10 a TC-13, TC-15 e TC-17). */
class RelayPublicationIT extends RelayIT {

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** TC-4: {@code occurredAt} mantém o instante informado e difere de {@code created_at}. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void occurredAtMantemOInstanteInformado(RelayStrategyType strategy) {
        Instant occurredAt = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Envelope envelope = TestEnvelopes.of("agg-1", TestEnvelopes.TYPE, occurredAt, new byte[] {1});
        db.insert(envelope);
        Relay relay = relay(strategy, rabbitPublisher());

        runUntil(relay, "linha enviada", () -> db.isSent(strategy, envelope.messageId()));

        Received received = awaitMessages(1, Duration.ofSeconds(10)).get(0);
        assertEquals(ISO_MICROS.format(occurredAt), received.header("occurredAt"));
        assertTrue(db.count("SELECT COUNT(*) FROM outbox WHERE DATEDIFF(MINUTE, occurred_at, created_at) >= 150") > 0);
    }

    /** TC-5: um payload binário qualquer chega byte a byte. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void payloadBinarioEPreservado(RelayStrategyType strategy) {
        byte[] payload = {0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80};
        Envelope envelope = TestEnvelopes.of("agg-1", TestEnvelopes.TYPE, Instant.now(), payload);
        db.insert(envelope);
        Relay relay = relay(strategy, rabbitPublisher());

        runUntil(relay, "linha enviada", () -> db.isSent(strategy, envelope.messageId()));

        assertArrayEquals(payload, awaitMessages(1, Duration.ofSeconds(10)).get(0).body());
    }

    /** TC-10: a mensagem chega à fila com headers e properties do contrato, e a linha só passa a enviada depois. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void publicaComHeadersPropertiesECorpo(RelayStrategyType strategy) {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        assertFalse(db.isSent(strategy, envelope.messageId()));
        Relay relay = relay(strategy, rabbitPublisher());

        assertEquals(1, relay.runOnce());

        Received received = awaitMessages(1, Duration.ofSeconds(10)).get(0);
        assertArrayEquals(envelope.payload(), received.body());
        assertEquals(envelope.messageId().toString(), received.header("messageId"));
        assertEquals(envelope.correlationId().toString(), received.header("correlationId"));
        assertEquals(envelope.idempotencyKey(), received.header("idempotencyKey"));
        assertEquals(envelope.aggregateKey(), received.header("aggregateKey"));
        assertEquals(envelope.type(), received.header("type"));
        assertEquals("1", received.header("schemaVersion"));
        assertEquals(envelope.traceId(), received.header("traceId"));
        assertEquals(envelope.messageId().toString(), received.properties().getMessageId());
        assertEquals(envelope.correlationId().toString(), received.properties().getCorrelationId());
        assertEquals(envelope.type(), received.properties().getType());
        assertEquals(2, received.properties().getDeliveryMode());
        assertEquals("application/octet-stream", received.properties().getContentType());
        assertTrue(db.isSent(strategy, envelope.messageId()));
    }

    /** TC-11: falha de publicação mantém a linha pendente e nada chega à fila. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void falhaDePublicacaoMantemALinhaPendente(RelayStrategyType strategy) {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher()).failAll();
        Relay relay = relay(strategy, publisher);

        assertEquals(0, relay.runOnce());

        assertFalse(db.isSent(strategy, envelope.messageId()));
        assertEquals(1, db.pendingCount(strategy));
        assertEquals(Collections.emptyList(), readAvailable());
    }

    /** TC-12: mensagem devolvida por falta de fila ligada não é dada como enviada. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void mensagemDevolvidaNaoEDadaComoEnviada(RelayStrategyType strategy) throws IOException {
        String type = "TipoSemFila";
        String lateQueue = "outbox-test-late-" + java.util.UUID.randomUUID();
        Envelope envelope = TestEnvelopes.of("agg-1", type);
        db.insert(envelope);
        Relay relay = relay(strategy, rabbitPublisher(), "backoff.initial", "100ms");

        assertEquals(0, relay.runOnce());
        assertFalse(db.isSent(strategy, envelope.messageId()));

        try {
            RabbitTopology.declareQuorumQueue(inspectionChannel(), lateQueue, EXCHANGE, type);
            runUntil(relay, "linha enviada depois de ligar a fila", () -> db.isSent(strategy, envelope.messageId()));
            assertEquals(1, readQueue(lateQueue).size());
        } finally {
            inspectionChannel().queueDelete(lateQueue);
        }
    }

    /** TC-13: queda depois do confirm e antes do registro republica a mensagem. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void quedaDepoisDoConfirmRepublicaAMensagem(RelayStrategyType strategy) {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        AtomicBoolean crashed = new AtomicBoolean();
        ScriptedPublisher crashing = new ScriptedPublisher(rabbitPublisher()).after(message -> {
            if (crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("queda simulada depois do confirm");
            }
        });
        Relay first = relayAs(strategy, crashing, "relay-a");

        assertThrows(IllegalStateException.class, first::runOnce);
        first.close();
        assertFalse(db.isSent(strategy, envelope.messageId()));

        Relay second = relayAs(strategy, rabbitPublisher(), "relay-b");
        runUntil(second, "linha enviada", () -> db.isSent(strategy, envelope.messageId()));

        List<Received> received = awaitMessages(2, Duration.ofSeconds(10));
        assertEquals(2, received.size());
        assertEquals(envelope.messageId().toString(), received.get(0).header("messageId"));
        assertEquals(envelope.messageId().toString(), received.get(1).header("messageId"));
    }

    /** TC-15: outra implementação de {@code MessagePublisher} substitui o broker sem alterar o núcleo. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void outraImplementacaoDePublisherSubstituiOBroker(RelayStrategyType strategy) {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        ScriptedPublisher inMemory = new ScriptedPublisher();
        Relay relay = relay(strategy, inMemory);

        assertEquals(1, relay.runOnce());

        assertEquals(1, inMemory.delivered().size());
        assertEquals(envelope.messageId(), inMemory.delivered().get(0).messageId());
        assertTrue(db.isSent(strategy, envelope.messageId()));
    }

    /** TC-17: erro de conexão com o broker mantém a linha pendente e não descarta nada. */
    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void erroDeConexaoMantemALinhaPendente(RelayStrategyType strategy) throws IOException {
        Envelope envelope = TestEnvelopes.of("agg-1");
        db.insert(envelope);
        broker.proxy().disable();
        ScriptedPublisher publisher = new ScriptedPublisher(rabbitPublisher());
        Relay relay = relay(strategy, publisher);

        assertEquals(0, relay.runOnce());

        assertEquals(1, db.outboxCount());
        assertEquals(1, db.pendingCount(strategy));
        assertEquals(1, publisher.attempts().size());
        assertFalse(publisher.attempts().get(0).succeeded());
    }
}
