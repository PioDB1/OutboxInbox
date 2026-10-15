package br.com.db1.poc.outbox.envelope;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EnvelopeTest {

    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T16:20:31.123456Z");

    private enum Campo { MESSAGE_ID, CORRELATION_ID, IDEMPOTENCY_KEY, AGGREGATE_KEY, TYPE, OCCURRED_AT, PAYLOAD, TRACE_ID }

    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }

    private static Envelope envelope(
            UUID messageId, UUID correlationId, String idempotencyKey, String aggregateKey, String type,
            Instant occurredAt, byte[] payload, String traceId) {
        return new Envelope(messageId, correlationId, idempotencyKey, aggregateKey, type, (short) 1,
                occurredAt, payload, traceId);
    }

    private static Envelope valido() {
        return envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1");
    }

    private static Envelope comCampoNulo(Campo campo) {
        return envelope(
                campo == Campo.MESSAGE_ID ? null : MESSAGE_ID,
                campo == Campo.CORRELATION_ID ? null : CORRELATION_ID,
                campo == Campo.IDEMPOTENCY_KEY ? null : "idem-1",
                campo == Campo.AGGREGATE_KEY ? null : "pagamento-1",
                campo == Campo.TYPE ? null : "PagamentoRecebido",
                campo == Campo.OCCURRED_AT ? null : OCCURRED_AT,
                campo == Campo.PAYLOAD ? null : new byte[] {1},
                campo == Campo.TRACE_ID ? null : "trace-1");
    }

    @ParameterizedTest
    @EnumSource(Campo.class)
    void rejeitaCampoNulo(Campo campo) {
        assertThrows(IllegalArgumentException.class, () -> comCampoNulo(campo));
    }

    @ParameterizedTest
    @EnumSource(value = Campo.class, names = {"IDEMPOTENCY_KEY", "AGGREGATE_KEY", "TYPE", "TRACE_ID"})
    void rejeitaTextoVazio(Campo campo) {
        assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID,
                campo == Campo.IDEMPOTENCY_KEY ? "" : "idem-1",
                campo == Campo.AGGREGATE_KEY ? "" : "pagamento-1",
                campo == Campo.TYPE ? "" : "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1},
                campo == Campo.TRACE_ID ? "" : "trace-1"));
    }

    @Test
    void rejeitaTextoAcimaDoTamanhoDaColuna() {
        assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, "idem-1", repeat("a", 101), "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1}, "trace-1"));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, repeat("i", 201), "pagamento-1", "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1}, "trace-1"));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", repeat("t", 101),
                OCCURRED_AT, new byte[] {1}, "trace-1"));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1}, repeat("x", 65)));
    }

    @Test
    void aceitaTextoNoTamanhoExatoDaColuna() {
        assertDoesNotThrow(() -> envelope(
                MESSAGE_ID, CORRELATION_ID, repeat("i", 200), repeat("a", 100), repeat("t", 100),
                OCCURRED_AT, new byte[0], repeat("x", 64)));
    }

    @Test
    void novoGeraMessageIdAleatorioACadaChamada() {
        Envelope primeiro = Envelope.novo(CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                (short) 1, OCCURRED_AT, new byte[] {1}, "trace-1");
        Envelope segundo = Envelope.novo(CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                (short) 1, OCCURRED_AT, new byte[] {1}, "trace-1");

        assertEquals(4, primeiro.messageId().version());
        assertNotEquals(primeiro.messageId(), segundo.messageId());
        assertEquals(CORRELATION_ID, primeiro.correlationId());
        assertEquals("trace-1", primeiro.traceId());
    }

    @Test
    void novoAplicaAsMesmasValidacoesDoConstrutor() {
        assertThrows(IllegalArgumentException.class, () -> Envelope.novo(CORRELATION_ID, "idem-1", "",
                "PagamentoRecebido", (short) 1, OCCURRED_AT, new byte[] {1}, "trace-1"));
    }

    @Test
    void preservaOInstanteDeOcorrencia() {
        assertEquals(OCCURRED_AT, valido().occurredAt());
    }

    @Test
    void payloadFicaIsoladoDoArrayDeOrigemEDoRetorno() {
        byte[] origem = {1, 2, 3};
        Envelope envelope = envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                OCCURRED_AT, origem, "trace-1");

        origem[0] = 9;
        byte[] lido = envelope.payload();
        lido[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, envelope.payload());
        assertNotSame(envelope.payload(), envelope.payload());
    }

    @Test
    void mensagemDeErroIdentificaOCampoInvalido() {
        IllegalArgumentException nulo = assertThrows(IllegalArgumentException.class, () -> comCampoNulo(Campo.TYPE));
        IllegalArgumentException vazio = assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, "", "pagamento-1", "PagamentoRecebido", OCCURRED_AT, new byte[] {1},
                "trace-1"));
        IllegalArgumentException longo = assertThrows(IllegalArgumentException.class, () -> envelope(
                MESSAGE_ID, CORRELATION_ID, "idem-1", repeat("a", 101), "PagamentoRecebido", OCCURRED_AT,
                new byte[] {1}, "trace-1"));

        assertEquals("type é obrigatório", nulo.getMessage());
        assertEquals("idempotencyKey não pode ser vazio", vazio.getMessage());
        assertEquals("aggregateKey excede 100 caracteres (101)", longo.getMessage());
    }

    @Test
    void acessoresDevolvemOsValoresInformados() {
        Envelope envelope = envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido",
                OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1");

        assertEquals(MESSAGE_ID, envelope.messageId());
        assertEquals(CORRELATION_ID, envelope.correlationId());
        assertEquals("idem-1", envelope.idempotencyKey());
        assertEquals("pagamento-1", envelope.aggregateKey());
        assertEquals("PagamentoRecebido", envelope.type());
        assertEquals((short) 1, envelope.schemaVersion());
        assertEquals(OCCURRED_AT, envelope.occurredAt());
        assertArrayEquals(new byte[] {1, 2, 3}, envelope.payload());
        assertEquals("trace-1", envelope.traceId());
    }

    @Test
    void toStringSegueOFormatoDoRegistroSemExporOPayload() {
        assertEquals("Envelope[messageId=" + MESSAGE_ID + ", correlationId=" + CORRELATION_ID
                + ", idempotencyKey=idem-1, aggregateKey=pagamento-1, type=PagamentoRecebido, schemaVersion=1, "
                + "occurredAt=" + OCCURRED_AT + ", payloadBytes=3, traceId=trace-1]", valido().toString());
    }

    @Test
    void igualdadeDiferenciaCadaCampo() {
        Envelope base = valido();

        assertEquals(base, base);
        assertNotEquals(base, null);
        assertNotEquals(base, "envelope");
        assertNotEquals(base, envelope(UUID.randomUUID(), CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, UUID.randomUUID(), "idem-1", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, CORRELATION_ID, "idem-2", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-2",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoEstornado", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT.plusSeconds(1), new byte[] {1, 2, 3}, "trace-1"));
        assertNotEquals(base, envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 3}, "trace-2"));
        assertNotEquals(base, new Envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoRecebido", (short) 2, OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1"));
    }

    @Test
    void igualdadeConsideraOConteudoDoPayload() {
        assertEquals(valido(), valido());
        assertEquals(valido().hashCode(), valido().hashCode());
        assertNotEquals(valido(), envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
                "PagamentoRecebido", OCCURRED_AT, new byte[] {1, 2, 4}, "trace-1"));
    }
}
