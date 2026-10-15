package br.com.db1.poc.outbox.publish;

import br.com.db1.poc.outbox.envelope.Envelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mensagem entregue ao {@link MessagePublisher}: os campos do {@link Envelope}, com o payload como bytes opacos. */
public final class PublishableMessage {

    private final UUID messageId;
    private final UUID correlationId;
    private final String idempotencyKey;
    private final String aggregateKey;
    private final String type;
    private final short schemaVersion;
    private final Instant occurredAt;
    private final byte[] payload;
    private final String traceId;

    public PublishableMessage(
            UUID messageId,
            UUID correlationId,
            String idempotencyKey,
            String aggregateKey,
            String type,
            short schemaVersion,
            Instant occurredAt,
            byte[] payload,
            String traceId) {
        this.messageId = messageId;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.aggregateKey = aggregateKey;
        this.type = type;
        this.schemaVersion = schemaVersion;
        this.occurredAt = occurredAt;
        this.payload = payload;
        this.traceId = traceId;
    }

    public static PublishableMessage from(Envelope envelope) {
        return new PublishableMessage(
                envelope.messageId(),
                envelope.correlationId(),
                envelope.idempotencyKey(),
                envelope.aggregateKey(),
                envelope.type(),
                envelope.schemaVersion(),
                envelope.occurredAt(),
                envelope.payload(),
                envelope.traceId());
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
        return payload;
    }

    public String traceId() {
        return traceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublishableMessage)) {
            return false;
        }
        PublishableMessage that = (PublishableMessage) other;
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(correlationId, that.correlationId)
                && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(aggregateKey, that.aggregateKey)
                && Objects.equals(type, that.type)
                && schemaVersion == that.schemaVersion
                && Objects.equals(occurredAt, that.occurredAt)
                && Objects.equals(payload, that.payload)
                && Objects.equals(traceId, that.traceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, correlationId, idempotencyKey, aggregateKey, type, schemaVersion,
                occurredAt, payload, traceId);
    }

    @Override
    public String toString() {
        return "PublishableMessage[messageId=" + messageId + ", correlationId=" + correlationId
                + ", idempotencyKey=" + idempotencyKey + ", aggregateKey=" + aggregateKey + ", type=" + type
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", payload=" + payload + ", traceId=" + traceId + "]";
    }
}
