package br.com.db1.poc.shared.config;

import br.com.db1.poc.shared.text.Texts;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura tipada de {@link Properties} cujas chaves compartilham um prefixo. Texto ausente ou em branco assume o
 * valor padrão; texto presente é lido sem os espaços das pontas. Valor inválido lança
 * {@link IllegalArgumentException} com a chave completa (prefixo e chave) e o valor lido.
 */
public final class PropertyReader {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private final Properties properties;
    private final String prefix;

    /**
     * @param properties propriedades lidas
     * @param prefix     prefixo comum das chaves, com o separador final (ex.: {@code outbox.relay.})
     */
    public PropertyReader(Properties properties, String prefix) {
        this.properties = properties;
        this.prefix = prefix;
    }

    /** Texto sem os espaços das pontas, ou {@code defaultValue} se a chave estiver ausente ou em branco. */
    public String text(String key, String defaultValue) {
        String value = properties.getProperty(prefix + key);
        return value == null || Texts.isBlank(value) ? defaultValue : Texts.strip(value);
    }

    public int integer(String key, int defaultValue) {
        String value = text(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + key + " deve ser um inteiro: '" + value + "'", e);
        }
    }

    public double decimal(String key, double defaultValue) {
        String value = text(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + key + " deve ser um número: '" + value + "'", e);
        }
    }

    /**
     * Duração em {@code ms}, {@code s}, {@code m}, {@code h} ou {@code d} (ex.: {@code 500ms}) ou em ISO-8601
     * (ex.: {@code PT2S}).
     *
     * @param defaultValue duração no mesmo formato, usada se a chave estiver ausente ou em branco
     */
    public Duration duration(String key, String defaultValue) {
        String value = text(key, defaultValue);
        Matcher matcher = DURATION.matcher(value);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            switch (matcher.group(2)) {
                case "ms":
                    return Duration.ofMillis(amount);
                case "s":
                    return Duration.ofSeconds(amount);
                case "m":
                    return Duration.ofMinutes(amount);
                case "h":
                    return Duration.ofHours(amount);
                default:
                    return Duration.ofDays(amount);
            }
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(prefix + key
                    + " deve ser uma duração (ex.: 500ms, 5s, 2m, 1h, 7d ou ISO-8601): '" + value + "'", e);
        }
    }
}
