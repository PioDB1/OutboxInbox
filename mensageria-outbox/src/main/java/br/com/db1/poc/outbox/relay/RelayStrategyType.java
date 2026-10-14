package br.com.db1.poc.outbox.relay;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Estratégia do relay, selecionada por {@code outbox.relay.strategy}. */
public enum RelayStrategyType {
    CLAIM("claim"),
    READ_POINT("read-point");

    private final String value;

    RelayStrategyType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Valores aceitos em {@code outbox.relay.strategy}, separados por vírgula. */
    public static String acceptedValues() {
        return Arrays.stream(values()).map(RelayStrategyType::value).collect(Collectors.joining(", "));
    }

    public static RelayStrategyType fromValue(String text) {
        for (RelayStrategyType type : values()) {
            if (type.value.equals(text)) {
                return type;
            }
        }
        throw new IllegalArgumentException("outbox.relay.strategy inválida: '" + text
                + "'; valores aceitos: " + acceptedValues());
    }
}
