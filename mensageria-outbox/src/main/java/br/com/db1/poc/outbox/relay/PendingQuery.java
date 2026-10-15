package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * Consulta somente leitura das linhas pendentes da outbox. O estado "pendente" depende da estratégia de relay
 * (claim: {@code sent_at IS NULL}; ponto de leitura: {@code rv} acima do ponto), por isso quem precisa saber se há
 * linhas sem publicação, como as métricas e o reconciliador, pergunta por aqui.
 *
 * <p>A consulta não espera as linhas de transações ainda abertas e nunca lê o {@code payload}.
 */
public interface PendingQuery {

    /** Pendentes da estratégia dada, lidas em uma conexão própria da {@code dataSource}. */
    PendingStats pending(DataSource dataSource, RelayStrategyType strategy) throws SQLException;

    /** Consulta que delega o SQL ao dialeto dado. */
    static PendingQuery create(OutboxDialect dialect) {
        return (dataSource, strategy) -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                OutboxDialect.PendingSummary summary;
                switch (strategy) {
                    case CLAIM:
                        summary = dialect.pendingUnsent(connection);
                        break;
                    case READ_POINT:
                        summary = dialect.pendingAheadOfReadPoint(connection);
                        break;
                    default:
                        throw new IllegalStateException("Estratégia não tratada: " + strategy);
                }
                return new PendingStats(summary.count(), Duration.ofMillis(summary.oldestAgeMillis()));
            }
        };
    }
}
