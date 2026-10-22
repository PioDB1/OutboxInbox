package br.com.db1.poc.inbox;

import br.com.db1.poc.shared.config.PropertyReader;
import br.com.db1.poc.shared.text.Texts;
import java.time.Duration;
import java.util.Properties;

/** Configuração do consumidor, lida das chaves {@code inbox.consumer.*}. */
public final class InboxConfig {

    public static final String PREFIX = "inbox.consumer.";

    private final String queue;
    private final String deadLetterQueue;
    private final int maxAttempts;
    private final Duration backoffInitial;
    private final double backoffMultiplier;
    private final Duration backoffMax;
    private final int maxInFlight;
    private final int workers;
    private final Duration confirmTimeout;

    /**
     * @param queue             fila principal (quorum, durável, declarada pela aplicação)
     * @param deadLetterQueue   DLQ (quorum, durável, declarada pela aplicação)
     * @param maxAttempts       tentativas de processamento por mensagem antes da DLQ; a primeira conta
     * @param backoffInitial    intervalo depois da primeira falha
     * @param backoffMultiplier fator de crescimento do intervalo
     * @param backoffMax        teto do intervalo
     * @param maxInFlight       mensagens sem ack, igual ao {@code basicQos}
     * @param workers           threads de trabalho que executam as filas por chave
     * @param confirmTimeout    espera do confirm da publicação na DLQ
     * @throws IllegalArgumentException valor recusado; a mensagem cita a chave
     */
    public InboxConfig(
            String queue,
            String deadLetterQueue,
            int maxAttempts,
            Duration backoffInitial,
            double backoffMultiplier,
            Duration backoffMax,
            int maxInFlight,
            int workers,
            Duration confirmTimeout) {
        if (queue == null || Texts.isBlank(queue)) {
            throw new IllegalArgumentException(PREFIX + "queue é obrigatória");
        }
        if (deadLetterQueue == null || Texts.isBlank(deadLetterQueue)) {
            throw new IllegalArgumentException(PREFIX + "dead-letter-queue não pode ser vazia");
        }
        if (queue.equals(deadLetterQueue)) {
            throw new IllegalArgumentException(
                    PREFIX + "dead-letter-queue deve ser diferente de " + PREFIX + "queue: " + queue);
        }
        requirePositive(maxAttempts, "max-attempts");
        requirePositive(maxInFlight, "max-in-flight");
        requirePositive(workers, "workers");
        new RetryBackoff(backoffInitial, backoffMultiplier, backoffMax);
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + "confirm-timeout deve ser uma duração positiva: " + confirmTimeout);
        }
        this.queue = queue;
        this.deadLetterQueue = deadLetterQueue;
        this.maxAttempts = maxAttempts;
        this.backoffInitial = backoffInitial;
        this.backoffMultiplier = backoffMultiplier;
        this.backoffMax = backoffMax;
        this.maxInFlight = maxInFlight;
        this.workers = workers;
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * Lê as chaves {@code inbox.consumer.*}; só {@code queue} é obrigatória, e as demais assumem os padrões do
     * componente.
     *
     * @throws IllegalArgumentException chave obrigatória ausente ou valor inválido
     */
    public static InboxConfig fromProperties(Properties properties) {
        PropertyReader reader = new PropertyReader(properties, PREFIX);
        String queue = reader.text("queue", null);
        if (queue == null) {
            throw new IllegalArgumentException(PREFIX + "queue é obrigatória");
        }
        return new InboxConfig(
                queue,
                reader.text("dead-letter-queue", queue + ".dlq"),
                reader.integer("max-attempts", 5),
                reader.duration("backoff.initial", "200ms"),
                reader.decimal("backoff.multiplier", 2.0),
                reader.duration("backoff.max", "30s"),
                reader.integer("max-in-flight", 50),
                reader.integer("workers", 8),
                reader.duration("confirm-timeout", "5s"));
    }

    public String queue() {
        return queue;
    }

    public String deadLetterQueue() {
        return deadLetterQueue;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration backoffInitial() {
        return backoffInitial;
    }

    public double backoffMultiplier() {
        return backoffMultiplier;
    }

    public Duration backoffMax() {
        return backoffMax;
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    public int workers() {
        return workers;
    }

    public Duration confirmTimeout() {
        return confirmTimeout;
    }

    @Override
    public String toString() {
        return "InboxConfig[queue=" + queue
                + ", deadLetterQueue=" + deadLetterQueue
                + ", maxAttempts=" + maxAttempts
                + ", backoffInitial=" + backoffInitial
                + ", backoffMultiplier=" + backoffMultiplier
                + ", backoffMax=" + backoffMax
                + ", maxInFlight=" + maxInFlight
                + ", workers=" + workers
                + ", confirmTimeout=" + confirmTimeout
                + "]";
    }

    private static void requirePositive(int value, String key) {
        if (value < 1) {
            throw new IllegalArgumentException(PREFIX + key + " deve ser maior ou igual a 1: " + value);
        }
    }
}
