package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Estratégia de ponto de leitura: a outbox só recebe {@code INSERT}, e o progresso do relay é o ponto de leitura
 * persistido em {@code outbox_read_point}.
 *
 * <p>Por ciclo: (1) lê o limite seguro {@code MIN_ACTIVE_ROWVERSION()}, sempre antes das linhas; (2) lê o ponto;
 * (3) lê até {@code batch-size} linhas com {@code ponto < rv < limite}, em ordem de {@code rv}; (4) publica em
 * ordem; (5) avança o ponto até o {@code rv} da última linha do prefixo contíguo publicado. Uma linha abaixo do
 * limite pertence a uma transação já encerrada, de modo que nenhuma linha é pulada e duas linhas do mesmo
 * {@code aggregateKey} entram na faixa lida apenas depois que ambas confirmam.
 *
 * <p>Quando uma linha falha ou está em backoff, o ponto fica antes dela e as linhas de outras chaves do lote
 * seguem; as publicadas à frente do ponto ficam em um conjunto em memória, limitado ao tamanho do lote, para não
 * serem republicadas enquanto a instância vive. Depois de um reinício elas são republicadas, o que o
 * at-least-once admite.
 */
public final class ReadPointRelay extends RelayRunner {

    private final Set<Long> publishedAhead = new HashSet<>();

    public ReadPointRelay(RelayConfig config, DataSource dataSource, OutboxDialect dialect,
            MessagePublisher publisher, MeterRegistry meterRegistry, String holderId) {
        super(config, dataSource, dialect, publisher, meterRegistry, holderId);
    }

    @Override
    protected int publishCycle() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            byte[] limit = dialect.minActiveRowversion(connection);
            byte[] point = dialect.readPoint(connection);
            long pointValue = toLong(point);
            publishedAhead.removeIf(rv -> Long.compareUnsigned(rv, pointValue) <= 0);
            List<OutboxRow> rows = dialect.readRange(connection, point, limit, config.batchSize());

            int published = 0;
            byte[] newPoint = null;
            boolean prefixIntact = true;
            Set<String> blocked = new HashSet<>();
            for (OutboxRow row : rows) {
                String key = row.aggregateKey();
                long rv = toLong(row.rv());
                boolean done = false;
                boolean abort = false;
                if (publishedAhead.contains(rv)) {
                    done = true;
                } else if (blocked.contains(key) || backoff.isBlocked(key)) {
                    blocked.add(key);
                } else if (!leaseValid()) {
                    abort = true;
                } else {
                    Outcome outcome = publish(row);
                    if (outcome == Outcome.PUBLISHED) {
                        publishedAhead.add(rv);
                        published++;
                        done = true;
                    } else {
                        blocked.add(key);
                        abort = outcome == Outcome.ABORT;
                    }
                }
                if (done && prefixIntact) {
                    newPoint = row.rv();
                } else {
                    prefixIntact = false;
                }
                if (abort) {
                    break;
                }
            }
            if (newPoint != null) {
                dialect.advanceReadPoint(connection, newPoint);
            }
            return published;
        }
    }

    private static long toLong(byte[] rv) {
        return ByteBuffer.wrap(rv).getLong();
    }
}
