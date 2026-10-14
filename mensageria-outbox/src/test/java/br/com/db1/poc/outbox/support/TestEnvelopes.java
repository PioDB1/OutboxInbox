package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.envelope.Envelope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Fábricas de envelopes de teste, com instantes em microssegundos (a precisão da coluna). */
public final class TestEnvelopes {

    public static final String TYPE = "PagamentoRecebido";

    private TestEnvelopes() {
    }

    public static Envelope of(String aggregateKey) {
        return of(aggregateKey, TYPE);
    }

    public static Envelope of(String aggregateKey, String type) {
        return of(aggregateKey, type, Instant.now().truncatedTo(ChronoUnit.MICROS), new byte[] {1, 2, 3});
    }

    public static Envelope of(String aggregateKey, String type, Instant occurredAt, byte[] payload) {
        return Envelope.novo(UUID.randomUUID(), "idem-" + UUID.randomUUID(), aggregateKey, type, (short) 1,
                occurredAt, payload, "trace-" + UUID.randomUUID());
    }
}
