package br.com.db1.poc.outbox.envelope;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope padrão da mensagem: campos de identificação, ordenação e propagação mais o payload opaco.
 *
 * <p>Os tamanhos máximos dos textos coincidem com as colunas da tabela {@code outbox}. O {@code payload} é
 * copiado na construção e na leitura, de modo que a instância permanece imutável.
 */
public final class Envelope {

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 200;
    public static final int AGGREGATE_KEY_MAX_LENGTH = 100;
    public static final int TYPE_MAX_LENGTH = 100;
    public static final int TRACE_ID_MAX_LENGTH = 64;

    private final UUID messageId;
    private final UUID correlationId;
    private final String idempotencyKey;
    private final String aggregateKey;
    private final String type;
    private final short schemaVersion;
    private final Instant occurredAt;
    private final byte[] payload;
    private final String traceId;

    public Envelope(
            UUID messageId,
            UUID correlationId,
            String idempotencyKey,
            String aggregateKey,
            String type,
            short schemaVersion,
            Instant occurredAt,
            byte[] payload,
            String traceId) {
        requireNonNull(messageId, "messageId");
        requireNonNull(correlationId, "correlationId");
        requireText(idempotencyKey, "idempotencyKey", IDEMPOTENCY_KEY_MAX_LENGTH);
        requireText(aggregateKey, "aggregateKey", AGGREGATE_KEY_MAX_LENGTH);
        requireText(type, "type", TYPE_MAX_LENGTH);
        requireNonNull(occurredAt, "occurredAt");
        requireNonNull(payload, "payload");
        requireText(traceId, "traceId", TRACE_ID_MAX_LENGTH);
        this.messageId = messageId;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.aggregateKey = aggregateKey;
        this.type = type;
        this.schemaVersion = schemaVersion;
        this.occurredAt = occurredAt;
        this.payload = payload.clone();
        this.traceId = traceId;
    }

    /** Cria um envelope com {@code messageId} aleatório (UUID v4). */
    public static Envelope novo(
            UUID correlationId,
            String idempotencyKey,
            String aggregateKey,
            String type,
            short schemaVersion,
            Instant occurredAt,
            byte[] payload,
            String traceId) {
        return new Envelope(UUID.randomUUID(), correlationId, idempotencyKey, aggregateKey, type,
                schemaVersion, occurredAt, payload, traceId);
    }

    public UUID messageId() {
        return messageId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String aggregateKey() {
        return aggregateKey;
    }

    public String type() {
        return type;
    }

    public short schemaVersion() {
        return schemaVersion;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public String traceId() {
        return traceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Envelope)) {
            return false;
        }
        Envelope that = (Envelope) other;
        return messageId.equals(that.messageId)
                && correlationId.equals(that.correlationId)
                && idempotencyKey.equals(that.idempotencyKey)
                && aggregateKey.equals(that.aggregateKey)
                && type.equals(that.type)
                && schemaVersion == that.schemaVersion
                && occurredAt.equals(that.occurredAt)
                && Arrays.equals(payload, that.payload)
                && traceId.equals(that.traceId);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(messageId, correlationId, idempotencyKey, aggregateKey, type, schemaVersion,
                occurredAt, traceId) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Envelope[messageId=" + messageId + ", correlationId=" + correlationId
                + ", idempotencyKey=" + idempotencyKey + ", aggregateKey=" + aggregateKey + ", type=" + type
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", payloadBytes=" + payload.length + ", traceId=" + traceId + "]";
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " não pode ser vazio");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " excede " + maxLength + " caracteres (" + value.length() + ")");
        }
    }
}
