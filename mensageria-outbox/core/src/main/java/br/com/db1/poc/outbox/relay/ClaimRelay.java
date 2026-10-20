package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Estratégia claim: reserva um lote de linhas pendentes com {@code UPDLOCK}/{@code READPAST}/{@code ROWLOCK},
 * publica em ordem de {@code seq}, marca {@code sent_at} depois de cada confirm e confirma a transação ao final
 * do lote. A linha só sai da fila de pendentes depois do confirm; uma queda antes do commit republica o lote.
 *
 * <p>{@code READPAST} ignora as linhas bloqueadas por transações ainda abertas, o que poderia publicar uma
 * mensagem de um {@code aggregateKey} antes de uma anterior da mesma chave. Por isso cada linha só é publicada se
 * não existir, entre ela e a linha anterior da mesma chave no lote, uma linha pendente — inclusive ainda não
 * confirmada ou reservada por outra instância, que a sonda enxerga com {@code READUNCOMMITTED}. Uma linha
 * bloqueada, em falha ou em backoff retém as posteriores da mesma chave, e as demais chaves seguem.
 */
public final class ClaimRelay extends RelayRunner {

    public ClaimRelay(RelayConfig config, DataSource dataSource, OutboxDialect dialect, MessagePublisher publisher,
            MeterRegistry meterRegistry, String holderId) {
        super(config, dataSource, dialect, publisher, meterRegistry, holderId);
    }

    @Override
    protected int publishCycle() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<OutboxRow> rows = dialect.claimPending(connection, config.batchSize());
                List<Long> sent = publishRows(connection, rows);
                dialect.markSent(connection, sent);
                connection.commit();
                return sent.size();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            }
        }
    }

    private List<Long> publishRows(Connection connection, List<OutboxRow> rows) throws SQLException {
        List<Long> sent = new ArrayList<>();
        Map<String, Long> previousSeqByKey = new HashMap<>();
        Set<String> blocked = new HashSet<>();
        for (OutboxRow row : rows) {
            String key = row.aggregateKey();
            long previousSeq = previousSeqByKey.getOrDefault(key, 0L);
            previousSeqByKey.put(key, row.seq());
            if (blocked.contains(key)) {
                continue;
            }
            if (backoff.isBlocked(key) || dialect.hasPendingBetween(connection, key, previousSeq, row.seq())) {
                blocked.add(key);
                continue;
            }
            if (!leaseValid()) {
                break;
            }
            Outcome outcome = publish(row);
            if (outcome == Outcome.PUBLISHED) {
                sent.add(row.seq());
            } else if (outcome == Outcome.FAILED) {
                blocked.add(key);
            } else {
                break;
            }
        }
        return sent;
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
