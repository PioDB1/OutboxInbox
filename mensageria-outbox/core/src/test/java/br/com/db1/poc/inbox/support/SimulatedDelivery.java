package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.shared.envelope.Envelope;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Entrega simulada: headers no formato publicado pelo {@code RabbitMessagePublisher} e corpo em bytes. */
public final class SimulatedDelivery implements Delivery {

    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final Map<String, Object> headers;
    private final byte[] body;

    private SimulatedDelivery(Map<String, Object> headers, byte[] body) {
        this.headers = headers;
        this.body = body;
    }

    /** Entrega com os oito headers do contrato e o payload do envelope como corpo. */
    public static SimulatedDelivery of(Envelope envelope) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("messageId", envelope.messageId().toString());
        headers.put("correlationId", envelope.correlationId().toString());
        headers.put("idempotencyKey", envelope.idempotencyKey());
        headers.put("aggregateKey", envelope.aggregateKey());
        headers.put("type", envelope.type());
        headers.put("schemaVersion", (int) envelope.schemaVersion());
        headers.put("occurredAt", OCCURRED_AT_FORMAT.format(envelope.occurredAt()));
        headers.put("traceId", envelope.traceId());
        return new SimulatedDelivery(headers, envelope.payload());
    }

    /** Cópia da entrega sem o header. */
    public SimulatedDelivery without(String name) {
        Map<String, Object> copy = new HashMap<>(headers);
        copy.remove(name);
        return new SimulatedDelivery(copy, body);
    }

    /** Cópia da entrega com o header trocado. */
    public SimulatedDelivery with(String name, Object value) {
        Map<String, Object> copy = new HashMap<>(headers);
        copy.put(name, value);
        return new SimulatedDelivery(copy, body);
    }

    @Override
    public Map<String, Object> headers() {
        return headers;
    }

    @Override
    public byte[] body() {
        return body;
    }

    public String messageId() {
        return String.valueOf(headers.get("messageId"));
    }
}
