package br.com.db1.poc.outbox.relay;

import java.time.Duration;

/**
 * Linhas pendentes da outbox.
 *
 * @param count     quantidade de linhas pendentes
 * @param oldestAge idade da linha pendente mais antiga; {@link Duration#ZERO} sem pendentes
 */
public record PendingStats(long count, Duration oldestAge) {

    public PendingStats {
        if (count < 0) {
            throw new IllegalArgumentException("count não pode ser negativo: " + count);
        }
        if (oldestAge == null || oldestAge.isNegative()) {
            throw new IllegalArgumentException("oldestAge deve ser uma duração não negativa: " + oldestAge);
        }
    }
}
