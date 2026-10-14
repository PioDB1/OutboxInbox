package br.com.db1.poc.outbox.relay;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Backoff exponencial determinístico, sem jitter, por {@code aggregateKey}: o intervalo depois da n-ésima falha
 * consecutiva é {@code min(max, inicial × fator^(n-1))}, e um sucesso zera a contagem da chave.
 *
 * <p>O estado vive em memória e tem no máximo {@code maxKeys} chaves; quando o limite é atingido, a chave com a
 * próxima tentativa mais próxima é descartada, o que apenas reinicia a sequência dela. A classe não é thread-safe:
 * é usada pela thread do relay.
 */
public final class BackoffPolicy {

    private record State(int failures, long nextAttemptNanos) {
    }

    private final long initialNanos;
    private final double multiplier;
    private final long maxNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final Map<String, State> states = new HashMap<>();

    public BackoffPolicy(Duration initial, double multiplier, Duration max, int maxKeys) {
        this(initial, multiplier, max, maxKeys, System::nanoTime);
    }

    BackoffPolicy(Duration initial, double multiplier, Duration max, int maxKeys, LongSupplier nanoClock) {
        this.initialNanos = initial.toNanos();
        this.multiplier = multiplier;
        this.maxNanos = max.toNanos();
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    /** Intervalo depois da {@code failures}-ésima falha consecutiva (a partir de 1). */
    public Duration delayAfter(int failures) {
        double nanos = initialNanos * Math.pow(multiplier, failures - 1);
        return Duration.ofNanos((long) Math.min(nanos, maxNanos));
    }

    /** Registra uma falha de {@code aggregateKey} e devolve o intervalo até a próxima tentativa permitida. */
    public Duration recordFailure(String aggregateKey) {
        State previous = states.get(aggregateKey);
        int failures = previous == null ? 1 : previous.failures() + 1;
        Duration delay = delayAfter(failures);
        if (previous == null && states.size() >= maxKeys) {
            evictOne();
        }
        states.put(aggregateKey, new State(failures, nanoClock.getAsLong() + delay.toNanos()));
        return delay;
    }

    /** Zera o backoff de {@code aggregateKey}. */
    public void recordSuccess(String aggregateKey) {
        states.remove(aggregateKey);
    }

    /** Número de falhas consecutivas registradas para a chave; zero sem backoff ativo. */
    public int failures(String aggregateKey) {
        State state = states.get(aggregateKey);
        return state == null ? 0 : state.failures();
    }

    /** Indica se a próxima tentativa de {@code aggregateKey} ainda não é permitida. */
    public boolean isBlocked(String aggregateKey) {
        State state = states.get(aggregateKey);
        return state != null && nanoClock.getAsLong() - state.nextAttemptNanos() < 0;
    }

    /** Tempo até a tentativa permitida mais próxima entre as chaves em backoff; vazio se nenhuma estiver bloqueada. */
    public Optional<Duration> untilNextAttempt() {
        long now = nanoClock.getAsLong();
        long soonest = Long.MAX_VALUE;
        for (State state : states.values()) {
            long remaining = state.nextAttemptNanos() - now;
            if (remaining > 0 && remaining < soonest) {
                soonest = remaining;
            }
        }
        return soonest == Long.MAX_VALUE ? Optional.empty() : Optional.of(Duration.ofNanos(soonest));
    }

    private void evictOne() {
        String victim = null;
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<String, State> entry : states.entrySet()) {
            long next = entry.getValue().nextAttemptNanos();
            if (victim == null || next - earliest < 0) {
                victim = entry.getKey();
                earliest = next;
            }
        }
        states.remove(victim);
    }
}
