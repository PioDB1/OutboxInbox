package br.com.db1.poc.outbox.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.relay.RelayStrategyType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class OutboxRetentionTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<String> calls = new ArrayList<>();

    /** Dialeto que devolve as contagens dadas, uma por chamada de remoção, e registra o método chamado. */
    private OutboxDialect dialect(Integer... deletions) {
        Iterator<Integer> results = Arrays.asList(deletions).iterator();
        return (OutboxDialect) Proxy.newProxyInstance(OutboxDialect.class.getClassLoader(),
                new Class<?>[] {OutboxDialect.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + "(" + args[1] + "," + args[2] + ")");
                    return results.hasNext() ? results.next() : 0;
                });
    }

    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(OutboxRetentionTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
        return (DataSource) Proxy.newProxyInstance(OutboxRetentionTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> connection);
    }

    private OutboxRetention retention(RelayStrategyType strategy, OutboxDialect dialect) {
        return new OutboxRetention(dialect, dataSource(), strategy, Duration.ofDays(7), Duration.ofSeconds(60), 10,
                clock::get);
    }

    @Test
    void removeEmLotesAteUmLoteParcial() throws SQLException {
        OutboxRetention retention = retention(RelayStrategyType.CLAIM, dialect(10, 10, 10, 5));

        assertEquals(35, retention.runNow(() -> true));
        assertEquals(Arrays.asList("deleteSentByTimestamp(604800000,10)", "deleteSentByTimestamp(604800000,10)",
                "deleteSentByTimestamp(604800000,10)", "deleteSentByTimestamp(604800000,10)"), calls);
    }

    @Test
    void estrategiaDePontoDeLeituraUsaARemocaoAtrasDoPonto() throws SQLException {
        OutboxRetention retention = retention(RelayStrategyType.READ_POINT, dialect(3));

        assertEquals(3, retention.runNow(() -> true));
        assertEquals(Arrays.asList("deleteBehindReadPoint(604800000,10)"), calls);
    }

    @Test
    void paraEntreLotesQuandoORelayDeixaDeSerOAtivo() throws SQLException {
        OutboxRetention retention = retention(RelayStrategyType.CLAIM, dialect(10, 10, 10));

        assertEquals(10, retention.runNow(() -> false));
    }

    @Test
    void respeitaOIntervaloEntreExecucoes() throws SQLException {
        OutboxRetention retention = retention(RelayStrategyType.CLAIM, dialect(4, 4, 4));

        assertEquals(4, retention.runIfDue(() -> true));
        clock.addAndGet(Duration.ofSeconds(59).toNanos());
        assertEquals(0, retention.runIfDue(() -> true));
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(4, retention.runIfDue(() -> true));
        assertEquals(2, calls.size());
    }
}
