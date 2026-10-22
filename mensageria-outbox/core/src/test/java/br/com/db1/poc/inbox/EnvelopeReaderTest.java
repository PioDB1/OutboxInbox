package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.TestEnvelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnvelopeReaderTest {

    private static byte[] allBytes() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    /** TC-1 (parte de leitura): os oito headers e o corpo viram o envelope, com microssegundos. */
    @Test
    void leOsHeadersEOCorpoSemAlteracao() {
        Envelope published = TestEnvelopes.of("agregado-1", "PagamentoRecebido", Instant.parse("2026-10-21T12:34:56.123456Z"),
                allBytes());

        Envelope read = EnvelopeReader.read(SimulatedDelivery.of(published));

        assertEquals(published, read);
        assertArrayEquals(allBytes(), read.payload());
        assertEquals(Instant.parse("2026-10-21T12:34:56.123456Z"), read.occurredAt());
    }

    /** TC-2: só os headers são lidos; a delivery nem expõe as properties AMQP. */
    @Test
    void headersSaoAUnicaFonte() {
        Envelope published = TestEnvelopes.of("agregado-1");
        SimulatedDelivery delivery = SimulatedDelivery.of(published);

        Envelope read = EnvelopeReader.read(delivery);

        assertEquals(published.messageId(), read.messageId());
        assertEquals(published.correlationId(), read.correlationId());
        assertEquals(published.type(), read.type());
    }

    @Test
    void schemaVersionComoNumeroOuComoTextoNumerico() {
        SimulatedDelivery base = SimulatedDelivery.of(TestEnvelopes.of("a"));

        assertEquals(7, EnvelopeReader.read(base.with("schemaVersion", 7L)).schemaVersion());
        assertEquals(7, EnvelopeReader.read(base.with("schemaVersion", (short) 7)).schemaVersion());
        assertEquals(7, EnvelopeReader.read(base.with("schemaVersion", "7")).schemaVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "correlationId", "idempotencyKey", "aggregateKey", "type", "schemaVersion",
            "occurredAt", "traceId"})
    void headerAusenteEInvalido(String header) {
        SimulatedDelivery delivery = SimulatedDelivery.of(TestEnvelopes.of("a")).without(header);

        InvalidEnvelopeException e = assertThrows(InvalidEnvelopeException.class, () -> EnvelopeReader.read(delivery));

        assertEquals("Header ausente: " + header, e.getMessage());
    }

    /** TC-3: as variações de header fora do formato que o leitor ou o {@code Envelope} rejeitam. */
    @Test
    void headersForaDoFormatoSaoInvalidos() {
        SimulatedDelivery base = SimulatedDelivery.of(TestEnvelopes.of("a"));

        assertInvalid(base.with("messageId", "não-é-uuid"));
        assertInvalid(base.with("messageId", "1-1-1-1-1"));
        assertInvalid(base.with("correlationId", "não-é-uuid"));
        assertInvalid(base.with("occurredAt", "2026-10-21 12:00:00"));
        assertInvalid(base.with("occurredAt", "2026-10-21T12:00:00+03:00"));
        assertInvalid(base.with("schemaVersion", "abc"));
        assertInvalid(base.with("schemaVersion", 1.5));
        assertInvalid(base.with("schemaVersion", 70_000));
        assertInvalid(base.with("type", repeat(101)));
        assertInvalid(base.with("aggregateKey", repeat(101)));
        assertInvalid(base.with("idempotencyKey", repeat(201)));
        assertInvalid(base.with("traceId", repeat(65)));
        assertInvalid(base.with("aggregateKey", ""));
    }

    @Test
    void aceitaUuidEmMaiusculas() {
        UUID id = UUID.randomUUID();
        SimulatedDelivery delivery =
                SimulatedDelivery.of(TestEnvelopes.of("a")).with("messageId", id.toString().toUpperCase());

        assertEquals(id, EnvelopeReader.read(delivery).messageId());
    }

    private static void assertInvalid(SimulatedDelivery delivery) {
        assertThrows(InvalidEnvelopeException.class, () -> EnvelopeReader.read(delivery));
    }

    private static String repeat(int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append('x');
        }
        return text.toString();
    }
}
