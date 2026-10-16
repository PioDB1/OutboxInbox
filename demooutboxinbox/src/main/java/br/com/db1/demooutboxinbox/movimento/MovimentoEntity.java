package br.com.db1.demooutboxinbox.movimento;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Movimentação de entrada ou saída, mapeada para a tabela {@code movimento}.
 * <p>
 * Nome, tipo e tamanho de cada coluna coincidem com a tabela gerada pelo Hibernate
 * ({@code ddl-auto=update}): uma divergência faz a subida emitir {@code alter column},
 * que no SQL Server deixa a coluna anulável.
 */
@Entity
@Table(name = "movimento")
@Getter
@Setter
@NoArgsConstructor
public class MovimentoEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	/** Instante da movimentação, gravado em UTC. */
	@Column(name = "data_hora", nullable = false, columnDefinition = "datetime2")
	private Instant dataHora;

	/** {@code E} para entrada e {@code S} para saída. */
	@Column(name = "tipo", nullable = false, length = 1, check = @CheckConstraint(constraint = "tipo in ('E','S')"))
	private char tipo;

	@Column(name = "descricao", nullable = false, length = 255)
	private String descricao;

	@JdbcTypeCode(SqlTypes.DECIMAL)
	@Column(name = "valor", nullable = false, precision = 18, scale = 2)
	private BigDecimal valor;

}
