package br.com.db1.poc.outbox.retention;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.relay.RelayStrategyType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Limpeza da outbox, executada pelo relay ativo em lotes.
 *
 * <p>Na estratégia claim remove as linhas com {@code sent_at} anterior à idade máxima; na de ponto de leitura,
 * as linhas com {@code rv} até o ponto de leitura e {@code created_at} anterior à idade máxima. Nenhuma das duas
 * remove linha pendente, e o limite seguro do ponto de leitura depende do {@code rv} persistido, não da
 * existência das linhas removidas.
 */
public final class OutboxRetention {

    private final OutboxDialect dialect;
    private final DataSource dataSource;
    private final RelayStrategyType strategy;
    private final long maxAgeMillis;
    private final long intervalNanos;
    private final int batchSize;
    private final LongSupplier nanoClock;

    private boolean ran;
    private long lastRunNanos;

    public OutboxRetention(OutboxDialect dialect, DataSource dataSource, RelayStrategyType strategy,
            Duration maxAge, Duration interval, int batchSize) {
        this(dialect, dataSource, strategy, maxAge, interval, batchSize, System::nanoTime);
    }

    OutboxRetention(OutboxDialect dialect, DataSource dataSource, RelayStrategyType strategy, Duration maxAge,
            Duration interval, int batchSize, LongSupplier nanoClock) {
        this.dialect = dialect;
        this.dataSource = dataSource;
        this.strategy = strategy;
        this.maxAgeMillis = maxAge.toMillis();
        this.intervalNanos = interval.toNanos();
        this.batchSize = batchSize;
        this.nanoClock = nanoClock;
    }

    /**
     * Executa a limpeza se o intervalo desde a última execução já passou.
     *
     * @param active consultado entre os lotes; a limpeza para quando devolve {@code false}
     * @return linhas removidas
     */
    public int runIfDue(BooleanSupplier active) throws SQLException {
        long now = nanoClock.getAsLong();
        if (ran && now - lastRunNanos < intervalNanos) {
            return 0;
        }
        ran = true;
        lastRunNanos = now;
        return runNow(active);
    }

    /** Executa a limpeza agora, em lotes de {@code retention.batch-size}, até um lote parcial. */
    public int runNow(BooleanSupplier active) throws SQLException {
        int total = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            int deleted;
            do {
                deleted = deleteBatch(connection);
                total += deleted;
            } while (deleted == batchSize && active.getAsBoolean());
        }
        return total;
    }

    private int deleteBatch(Connection connection) throws SQLException {
        return switch (strategy) {
            case CLAIM -> dialect.deleteSentByTimestamp(connection, maxAgeMillis, batchSize);
            case READ_POINT -> dialect.deleteBehindReadPoint(connection, maxAgeMillis, batchSize);
        };
    }
}
