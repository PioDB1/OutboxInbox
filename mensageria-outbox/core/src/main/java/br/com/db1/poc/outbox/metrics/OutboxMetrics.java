package br.com.db1.poc.outbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas de publicação da outbox, registradas em um {@link MeterRegistry}:
 *
 * <ul>
 *   <li>{@code outbox.lag.seconds} (gauge): idade, em segundos, da linha pendente mais antiga; zero sem pendentes;
 *   <li>{@code outbox.pending} (gauge): quantidade de linhas pendentes;
 *   <li>{@code outbox.publish.retries} (counter): tentativas de publicação de uma linha cujo {@code aggregateKey}
 *       já falhou antes.
 * </ul>
 *
 * <p>Os gauges guardam o último valor informado por {@link #update(long, Duration)}; o relay o chama a cada ciclo.
 * Nenhuma das métricas tem valor-meta. A instância precisa ser mantida por quem a criou: o registro referencia os
 * gauges de forma fraca. Cada {@link MeterRegistry} atende a um relay: um segundo relay no mesmo registro reaproveita
 * os medidores do primeiro, e os gauges dele não acompanham o segundo.
 */
public final class OutboxMetrics {

    public static final String LAG_SECONDS = "outbox.lag.seconds";
    public static final String PENDING = "outbox.pending";
    public static final String PUBLISH_RETRIES = "outbox.publish.retries";

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lagMillis = new AtomicLong();
    private final Counter retries;

    public OutboxMetrics(MeterRegistry registry) {
        Gauge.builder(LAG_SECONDS, lagMillis, millis -> millis.get() / 1000.0)
                .description("Idade da linha pendente mais antiga da outbox")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(PENDING, pending, AtomicLong::get)
                .description("Linhas pendentes da outbox")
                .register(registry);
        this.retries = Counter.builder(PUBLISH_RETRIES)
                .description("Tentativas de publicação de uma linha que já falhou antes")
                .register(registry);
    }

    /** Atualiza os gauges com o estado observado da outbox. */
    public void update(long pendingCount, Duration oldestAge) {
        pending.set(pendingCount);
        lagMillis.set(oldestAge.toMillis());
    }

    /** Conta uma nova tentativa de publicação de linha que já falhou. */
    public void recordRetry() {
        retries.increment();
    }
}
