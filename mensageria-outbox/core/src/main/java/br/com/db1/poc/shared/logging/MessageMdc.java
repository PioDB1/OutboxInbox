package br.com.db1.poc.shared.logging;

import java.util.Arrays;
import org.slf4j.MDC;

/**
 * Coloca no MDC as chaves {@code messageId}, {@code correlationId}, {@code aggregateKey} e {@code traceId} de uma
 * mensagem, para que todo log escrito durante o tratamento dela as traga. Pensado para {@code try} com recursos; ao
 * fechar, remove as chaves que colocou.
 */
public final class MessageMdc implements AutoCloseable {

    public static final String MESSAGE_ID = "messageId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String AGGREGATE_KEY = "aggregateKey";
    public static final String TRACE_ID = "traceId";

    private final String[] keys;

    private MessageMdc(String[] keys) {
        this.keys = keys;
    }

    /** Abre o MDC da mensagem; um valor nulo não coloca a respectiva chave. */
    public static MessageMdc open(String messageId, String correlationId, String aggregateKey, String traceId) {
        String[] keys = {MESSAGE_ID, CORRELATION_ID, AGGREGATE_KEY, TRACE_ID};
        String[] values = {messageId, correlationId, aggregateKey, traceId};
        String[] placed = new String[keys.length];
        int count = 0;
        for (int i = 0; i < keys.length; i++) {
            if (values[i] != null) {
                MDC.put(keys[i], values[i]);
                placed[count++] = keys[i];
            }
        }
        return new MessageMdc(Arrays.copyOf(placed, count));
    }

    /** Remove do MDC as chaves colocadas por {@link #open}. */
    @Override
    public void close() {
        for (String key : keys) {
            MDC.remove(key);
        }
    }
}
