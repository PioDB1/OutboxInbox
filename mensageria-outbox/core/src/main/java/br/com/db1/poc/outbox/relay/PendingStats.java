package br.com.db1.poc.outbox.relay;

import java.time.Duration;
import java.util.Objects;

/** Linhas pendentes da outbox. */
public final class PendingStats {

    private final long count;
    private final Duration oldestAge;

    /**
     * @param count     quantidade de linhas pendentes
     * @param oldestAge idade da linha pendente mais antiga; {@link Duration#ZERO} sem pendentes
     */
    public PendingStats(long count, Duration oldestAge) {
        if (count < 0) {
            throw new IllegalArgumentException("count não pode ser negativo: " + count);
        }
        if (oldestAge == null || oldestAge.isNegative()) {
            throw new IllegalArgumentException("oldestAge deve ser uma duração não negativa: " + oldestAge);
        }
        this.count = count;
        this.oldestAge = oldestAge;
    }

    public long count() {
        return count;
    }

    public Duration oldestAge() {
        return oldestAge;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PendingStats)) {
            return false;
        }
        PendingStats that = (PendingStats) other;
        return count == that.count && Objects.equals(oldestAge, that.oldestAge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, oldestAge);
    }

    @Override
    public String toString() {
        return "PendingStats[count=" + count + ", oldestAge=" + oldestAge + "]";
    }
}
