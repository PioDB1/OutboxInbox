package br.com.db1.poc.inbox;

import java.time.Duration;

/**
 * Intervalo crescente entre as tentativas: depois da n-ésima falha, {@code min(max, initial × multiplier^(n-1))}.
 * Também espaça as repetições da publicação na DLQ.
 */
public final class RetryBackoff {

    private final Duration initial;
    private final double multiplier;
    private final Duration max;

    /**
     * @throws IllegalArgumentException {@code initial} ou {@code max} não positivo, {@code multiplier} {@code NaN},
     *                                  infinito ou menor que 1,0, ou {@code max} menor que {@code initial}
     */
    public RetryBackoff(Duration initial, double multiplier, Duration max) {
        if (initial == null || initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException(
                    InboxConfig.PREFIX + "backoff.initial deve ser uma duração positiva: " + initial);
        }
        if (max == null || max.isZero() || max.isNegative()) {
            throw new IllegalArgumentException(
                    InboxConfig.PREFIX + "backoff.max deve ser uma duração positiva: " + max);
        }
        if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException(
                    InboxConfig.PREFIX + "backoff.multiplier deve ser um número finito maior ou igual a 1.0: "
                            + multiplier);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException(
                    InboxConfig.PREFIX + "backoff.max não pode ser menor que backoff.initial");
        }
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
    }

    /** Espera, em milissegundos, depois da {@code failures}-ésima falha ({@code failures} a partir de 1). */
    public long delayMillis(int failures) {
        double maxMillis = (double) max.toMillis();
        double delay = initial.toMillis() * Math.pow(multiplier, Math.max(0, failures - 1));
        return (long) Math.min(maxMillis, delay);
    }
}
