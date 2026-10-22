package br.com.db1.poc.inbox;

import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.logging.MessageMdc;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lê o {@link Envelope} dos headers de uma {@link Delivery}; o payload é o corpo da mensagem, sem alteração. Os
 * headers são a única fonte: as properties AMQP {@code message_id}, {@code correlation_id} e {@code type} apenas
 * espelham os headers.
 */
public final class EnvelopeReader {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern UTC_INSTANT_FORMAT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");

    private EnvelopeReader() {
    }

    /**
     * @throws InvalidEnvelopeException header ausente, {@code messageId} ou {@code correlationId} fora do formato
     *                                  UUID, {@code occurredAt} fora de ISO-8601 UTC, {@code schemaVersion} não
     *                                  numérico ou fora do intervalo de {@code short}, ou texto vazio ou acima do
     *                                  tamanho máximo do {@link Envelope}
     */
    public static Envelope read(Delivery delivery) {
        Map<String, Object> headers = delivery.headers();
        UUID messageId = uuid(headers, MessageMdc.MESSAGE_ID);
        UUID correlationId = uuid(headers, MessageMdc.CORRELATION_ID);
        String idempotencyKey = text(headers, "idempotencyKey");
        String aggregateKey = text(headers, MessageMdc.AGGREGATE_KEY);
        String type = text(headers, "type");
        short schemaVersion = schemaVersion(headers);
        Instant occurredAt = occurredAt(headers);
        String traceId = text(headers, MessageMdc.TRACE_ID);
        try {
            return new Envelope(messageId, correlationId, idempotencyKey, aggregateKey, type, schemaVersion,
                    occurredAt, delivery.body(), traceId);
        } catch (IllegalArgumentException e) {
            throw new InvalidEnvelopeException("Envelope inválido: " + e.getMessage(), e);
        }
    }

    /** Valor textual do header, ou {@code null} se ele não existe; usado para o MDC antes da leitura completa. */
    static String optionalText(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Map<String, Object> headers, String name) {
        String value = optionalText(headers, name);
        if (value == null) {
            throw new InvalidEnvelopeException("Header ausente: " + name);
        }
        return value;
    }

    private static UUID uuid(Map<String, Object> headers, String name) {
        String value = text(headers, name);
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new InvalidEnvelopeException("Header " + name + " não é um UUID: '" + value + "'");
        }
        return UUID.fromString(value);
    }

    private static short schemaVersion(Map<String, Object> headers) {
        Object value = headers.get("schemaVersion");
        if (value == null) {
            throw new InvalidEnvelopeException("Header ausente: schemaVersion");
        }
        long number;
        if (value instanceof Number) {
            Number numeric = (Number) value;
            number = numeric.longValue();
            if (numeric.doubleValue() != (double) number) {
                throw new InvalidEnvelopeException("Header schemaVersion não é um inteiro: '" + value + "'");
            }
        } else {
            try {
                number = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new InvalidEnvelopeException("Header schemaVersion não é numérico: '" + value + "'", e);
            }
        }
        if (number < Short.MIN_VALUE || number > Short.MAX_VALUE) {
            throw new InvalidEnvelopeException("Header schemaVersion fora do intervalo de short: " + number);
        }
        return (short) number;
    }

    private static Instant occurredAt(Map<String, Object> headers) {
        String value = text(headers, "occurredAt");
        try {
            // Instant.parse aceita deslocamentos como +03:00 a partir do JDK 12; o contrato só admite UTC (sufixo Z).
            if (!UTC_INSTANT_FORMAT.matcher(value).matches()) {
                throw new DateTimeParseException("fora do formato UTC com sufixo Z", value, 0);
            }
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidEnvelopeException("Header occurredAt não está em ISO-8601 UTC: '" + value + "'", e);
        }
    }
}
