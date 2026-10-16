package br.com.db1.demooutboxinbox.movimento;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Corpo da requisição de {@code POST /movimentos}.
 * <p>
 * {@code tipo} é {@link Character}: o JSON {@code null} chega como {@code null} à verificação
 * de presença, que o primitivo {@code char} receberia como {@code '\0'}.
 */
public record MovimentoRequest(Instant dataHora, Character tipo, String descricao, BigDecimal valor) {

}
