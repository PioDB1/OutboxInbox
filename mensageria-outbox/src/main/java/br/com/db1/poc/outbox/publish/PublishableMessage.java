package br.com.db1.poc.outbox.publish;

import br.com.db1.poc.outbox.envelope.Envelope;
import java.time.Instant;
import java.util.UUID;

/** Mensagem entregue ao {@link MessagePublisher}: os campos do {@link Envelope}, com o payload como bytes opacos. */
public record PublishableMessage(
        UUID messageId,
        UUID correlationId,
        String idempotencyKey,
        String aggregateKey,
        String type,
        short schemaVersion,
        Instant occurredAt,
        byte[] payload,
        String traceId) {

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
}
