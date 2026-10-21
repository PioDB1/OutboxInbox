package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.shared.config.PropertyReader;
import br.com.db1.poc.shared.text.Texts;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * Configuração do relay, lida das chaves {@code outbox.relay.*}.
 */
public final class RelayConfig {

    public static final String PREFIX = "outbox.relay.";

    private final boolean enabled;
    private final RelayStrategyType strategy;
    private final String exchange;
    private final int batchSize;
    private final Duration pollInterval;
    private final Duration confirmTimeout;
    private final Duration backoffInitial;
    private final double backoffMultiplier;
    private final Duration backoffMax;
    private final Duration leaseDuration;
    private final Duration leaseRenewInterval;
    private final Duration retentionMaxAge;
    private final Duration retentionInterval;
    private final int retentionBatchSize;

    /**
     * @param enabled           liga o relay na instância; só deve ser {@code true} em banco primário/ativo
     * @param strategy          estratégia de relay
     * @param exchange          exchange de publicação
     * @param batchSize         linhas por ciclo
     * @param pollInterval      espera entre ciclos sem trabalho
     * @param confirmTimeout    espera pelo confirm de cada mensagem
     * @param backoffInitial    primeiro intervalo de backoff de um {@code aggregateKey}
     * @param backoffMultiplier fator de crescimento do backoff
     * @param backoffMax        teto do backoff
     * @param leaseDuration     validade do lease a cada aquisição ou renovação
     * @param leaseRenewInterval intervalo entre renovações do lease
     * @param retentionMaxAge   idade mínima de uma linha enviada para ser removida
     * @param retentionInterval frequência da limpeza
     * @param retentionBatchSize linhas removidas por comando de limpeza
     */
    public RelayConfig(
            boolean enabled,
            RelayStrategyType strategy,
            String exchange,
            int batchSize,
            Duration pollInterval,
            Duration confirmTimeout,
            Duration backoffInitial,
            double backoffMultiplier,
            Duration backoffMax,
            Duration leaseDuration,
            Duration leaseRenewInterval,
            Duration retentionMaxAge,
            Duration retentionInterval,
            int retentionBatchSize) {
        if (strategy == null) {
            throw new IllegalArgumentException(PREFIX + "strategy é obrigatória; valores aceitos: "
                    + RelayStrategyType.acceptedValues());
        }
        if (exchange == null || Texts.isBlank(exchange)) {
            throw new IllegalArgumentException(PREFIX + "exchange não pode ser vazia");
        }
        requirePositive(batchSize, "batch-size");
        requirePositive(retentionBatchSize, "retention.batch-size");
        requirePositive(pollInterval, "poll-interval");
        requirePositive(confirmTimeout, "confirm-timeout");
        requirePositive(backoffInitial, "backoff.initial");
        requirePositive(backoffMax, "backoff.max");
        requirePositive(leaseDuration, "lease.duration");
        requirePositive(leaseRenewInterval, "lease.renew-interval");
        requirePositive(retentionMaxAge, "retention.max-age");
        requirePositive(retentionInterval, "retention.interval");
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException(PREFIX + "backoff.multiplier deve ser maior ou igual a 1.0");
        }
        if (backoffMax.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException(PREFIX + "backoff.max não pode ser menor que backoff.initial");
        }
        if (leaseRenewInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(PREFIX + "lease.renew-interval deve ser menor que lease.duration");
        }
        this.enabled = enabled;
        this.strategy = strategy;
        this.exchange = exchange;
        this.batchSize = batchSize;
        this.pollInterval = pollInterval;
        this.confirmTimeout = confirmTimeout;
        this.backoffInitial = backoffInitial;
        this.backoffMultiplier = backoffMultiplier;
        this.backoffMax = backoffMax;
        this.leaseDuration = leaseDuration;
        this.leaseRenewInterval = leaseRenewInterval;
        this.retentionMaxAge = retentionMaxAge;
        this.retentionInterval = retentionInterval;
        this.retentionBatchSize = retentionBatchSize;
    }

    public boolean enabled() {
        return enabled;
    }

    public RelayStrategyType strategy() {
        return strategy;
    }

    public String exchange() {
        return exchange;
    }

    public int batchSize() {
        return batchSize;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public Duration confirmTimeout() {
        return confirmTimeout;
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

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Duration leaseRenewInterval() {
        return leaseRenewInterval;
    }

    public Duration retentionMaxAge() {
        return retentionMaxAge;
    }

    public Duration retentionInterval() {
        return retentionInterval;
    }

    public int retentionBatchSize() {
        return retentionBatchSize;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelayConfig)) {
            return false;
        }
        RelayConfig that = (RelayConfig) other;
        return enabled == that.enabled
                && Objects.equals(strategy, that.strategy)
                && Objects.equals(exchange, that.exchange)
                && batchSize == that.batchSize
                && Objects.equals(pollInterval, that.pollInterval)
                && Objects.equals(confirmTimeout, that.confirmTimeout)
                && Objects.equals(backoffInitial, that.backoffInitial)
                && Double.compare(backoffMultiplier, that.backoffMultiplier) == 0
                && Objects.equals(backoffMax, that.backoffMax)
                && Objects.equals(leaseDuration, that.leaseDuration)
                && Objects.equals(leaseRenewInterval, that.leaseRenewInterval)
                && Objects.equals(retentionMaxAge, that.retentionMaxAge)
                && Objects.equals(retentionInterval, that.retentionInterval)
                && retentionBatchSize == that.retentionBatchSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, strategy, exchange, batchSize, pollInterval, confirmTimeout, backoffInitial,
                backoffMultiplier, backoffMax, leaseDuration, leaseRenewInterval, retentionMaxAge,
                retentionInterval, retentionBatchSize);
    }

    @Override
    public String toString() {
        return "RelayConfig[" + "enabled=" + enabled
                + ", strategy=" + strategy
                + ", exchange=" + exchange
                + ", batchSize=" + batchSize
                + ", pollInterval=" + pollInterval
                + ", confirmTimeout=" + confirmTimeout
                + ", backoffInitial=" + backoffInitial
                + ", backoffMultiplier=" + backoffMultiplier
                + ", backoffMax=" + backoffMax
                + ", leaseDuration=" + leaseDuration
                + ", leaseRenewInterval=" + leaseRenewInterval
                + ", retentionMaxAge=" + retentionMaxAge
                + ", retentionInterval=" + retentionInterval
                + ", retentionBatchSize=" + retentionBatchSize
                + "]";
    }

    /**
     * Lê as chaves {@code outbox.relay.*}; chaves ausentes assumem os valores padrão.
     *
     * @throws IllegalArgumentException estratégia ausente ou desconhecida (a mensagem cita os valores aceitos) ou
     *                                  valor inválido
     */
    public static RelayConfig fromProperties(Properties properties) {
        PropertyReader reader = new PropertyReader(properties, PREFIX);
        String strategy = reader.text("strategy", null);
        if (strategy == null) {
            throw new IllegalArgumentException(PREFIX + "strategy é obrigatória; valores aceitos: "
                    + RelayStrategyType.acceptedValues());
        }
        return new RelayConfig(
                Boolean.parseBoolean(reader.text("enabled", "false")),
                RelayStrategyType.fromValue(strategy),
                reader.text("exchange", "mensageria.outbox"),
                reader.integer("batch-size", 100),
                reader.duration("poll-interval", "500ms"),
                reader.duration("confirm-timeout", "5s"),
                reader.duration("backoff.initial", "200ms"),
                reader.decimal("backoff.multiplier", 2.0),
                reader.duration("backoff.max", "30s"),
                reader.duration("lease.duration", "15s"),
                reader.duration("lease.renew-interval", "5s"),
                reader.duration("retention.max-age", "7d"),
                reader.duration("retention.interval", "60s"),
                reader.integer("retention.batch-size", 1000));
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(PREFIX + key + " deve ser positivo: " + value);
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + key + " deve ser uma duração positiva: " + value);
        }
    }
}
