package br.com.db1.poc.inbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas de consumo da inbox, registradas em um {@link MeterRegistry}:
 *
 * <ul>
 *   <li>{@code inbox.duplicates.discarded} (counter): mensagens descartadas porque o {@code messageId} já está na
 *       inbox;
 *   <li>{@code inbox.dlq.messages} (counter): mensagens publicadas na DLQ;
 *   <li>{@code inbox.process.retries} (counter): repetições do processamento de uma mensagem que falhou.
 * </ul>
 *
 * <p>Nenhuma das métricas tem valor-meta.
 */
public final class InboxMetrics {

    public static final String DUPLICATES_DISCARDED = "inbox.duplicates.discarded";
    public static final String DLQ_MESSAGES = "inbox.dlq.messages";
    public static final String PROCESS_RETRIES = "inbox.process.retries";

    private final Counter duplicates;
    private final Counter deadLetters;
    private final Counter retries;

    public InboxMetrics(MeterRegistry registry) {
        this.duplicates = Counter.builder(DUPLICATES_DISCARDED)
                .description("Mensagens descartadas porque o messageId já está na inbox")
                .register(registry);
        this.deadLetters = Counter.builder(DLQ_MESSAGES)
                .description("Mensagens publicadas na DLQ")
                .register(registry);
        this.retries = Counter.builder(PROCESS_RETRIES)
                .description("Repetições do processamento de mensagens que falharam")
                .register(registry);
    }

    public void recordDuplicate() {
        duplicates.increment();
    }

    public void recordDeadLetter() {
        deadLetters.increment();
    }

    public void recordRetry() {
        retries.increment();
    }
}
