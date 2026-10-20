package br.com.db1.demooutboxinbox.movimento;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Corpo da resposta {@code 201} de {@code POST /movimentos/inclusao} e {@code POST /movimentos/inclusao_sem_evento}:
 * a movimentação como o banco a registrou.
 */
public record MovimentoResponse(Long id, Instant dataHora, Character tipo, String descricao, BigDecimal valor) {

	public static MovimentoResponse from(MovimentoEntity entidade) {
		return new MovimentoResponse(entidade.getId(), entidade.getDataHora(), entidade.getTipo(),
				entidade.getDescricao(), entidade.getValor());
	}

}
