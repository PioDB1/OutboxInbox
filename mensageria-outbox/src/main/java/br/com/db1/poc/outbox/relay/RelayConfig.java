package br.com.db1.poc.outbox.relay;

import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuração do relay, lida das chaves {@code outbox.relay.*}.
 *
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
public record RelayConfig(
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

    public static final String PREFIX = "outbox.relay.";

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    public RelayConfig {
        if (strategy == null) {
            throw new IllegalArgumentException(PREFIX + "strategy é obrigatória; valores aceitos: "
                    + RelayStrategyType.acceptedValues());
        }
        if (exchange == null || exchange.isBlank()) {
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
    }

    /**
     * Lê as chaves {@code outbox.relay.*}; chaves ausentes assumem os valores padrão.
     *
     * @throws IllegalArgumentException estratégia ausente ou desconhecida (a mensagem cita os valores aceitos) ou
     *                                  valor inválido
     */
    public static RelayConfig fromProperties(Properties properties) {
        String strategy = text(properties, "strategy", null);
        if (strategy == null) {
            throw new IllegalArgumentException(PREFIX + "strategy é obrigatória; valores aceitos: "
                    + RelayStrategyType.acceptedValues());
        }
        return new RelayConfig(
                Boolean.parseBoolean(text(properties, "enabled", "false")),
                RelayStrategyType.fromValue(strategy),
                text(properties, "exchange", "mensageria.outbox"),
                integer(properties, "batch-size", 100),
                duration(properties, "poll-interval", "500ms"),
                duration(properties, "confirm-timeout", "5s"),
                duration(properties, "backoff.initial", "200ms"),
                decimal(properties, "backoff.multiplier", 2.0),
                duration(properties, "backoff.max", "30s"),
                duration(properties, "lease.duration", "15s"),
                duration(properties, "lease.renew-interval", "5s"),
                duration(properties, "retention.max-age", "7d"),
                duration(properties, "retention.interval", "60s"),
                integer(properties, "retention.batch-size", 1000));
    }

    private static String text(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(PREFIX + key);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        String value = text(properties, key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + key + " deve ser um inteiro: '" + value + "'", e);
        }
    }

    private static double decimal(Properties properties, String key, double defaultValue) {
        String value = text(properties, key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + key + " deve ser um número: '" + value + "'", e);
        }
    }

    private static Duration duration(Properties properties, String key, String defaultValue) {
        String value = text(properties, key, defaultValue);
        Matcher matcher = DURATION.matcher(value);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(PREFIX + key
                    + " deve ser uma duração (ex.: 500ms, 5s, 2m, 1h, 7d ou ISO-8601): '" + value + "'", e);
        }
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
