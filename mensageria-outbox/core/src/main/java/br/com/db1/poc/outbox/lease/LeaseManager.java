package br.com.db1.poc.outbox.lease;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Lease do relay ativo, guardado na tabela {@code outbox_lease} do banco de origem. A aquisição e a renovação
 * são um {@code UPDATE} condicional no relógio do banco, comum a todas as instâncias; o lease expira sozinho
 * quando o detentor deixa de renová-lo.
 *
 * <p>O lease não carrega token de fencing: uma instância congelada além da validade pode publicar um ciclo
 * concorrente com o novo detentor, e as duplicidades resultantes são absorvidas pelo dedup do consumidor.
 *
 * <p>A instância guarda apenas o instante da última aquisição ou renovação, para renovar a cada
 * {@code renewInterval}; todo o estado de que o relay precisa para retomar está no banco. Usada pela thread do
 * relay e por {@code close()}, por isso os métodos são sincronizados.
 */
public final class LeaseManager {

    private final OutboxDialect dialect;
    private final DataSource dataSource;
    private final String holderId;
    private final long durationMillis;
    private final long renewIntervalNanos;
    private final LongSupplier nanoClock;

    private boolean held;
    private long lastRenewNanos;

    public LeaseManager(OutboxDialect dialect, DataSource dataSource, String holderId, Duration duration,
            Duration renewInterval) {
        this(dialect, dataSource, holderId, duration, renewInterval, System::nanoTime);
    }

    LeaseManager(OutboxDialect dialect, DataSource dataSource, String holderId, Duration duration,
            Duration renewInterval, LongSupplier nanoClock) {
        this.dialect = dialect;
        this.dataSource = dataSource;
        this.holderId = holderId;
        this.durationMillis = duration.toMillis();
        this.renewIntervalNanos = renewInterval.toNanos();
        this.nanoClock = nanoClock;
    }

    public String holderId() {
        return holderId;
    }

    /**
     * Garante o lease: devolve {@code true} sem consultar o banco enquanto a última aquisição ou renovação é
     * mais recente que o intervalo de renovação; do contrário tenta adquirir ou renovar.
     *
     * @return {@code true} se esta instância detém o lease
     */
    public synchronized boolean ensureHeld() throws SQLException {
        if (held && nanoClock.getAsLong() - lastRenewNanos < renewIntervalNanos) {
            return true;
        }
        return acquireOrRenew();
    }

    /** Tenta adquirir ou renovar o lease agora, sem considerar o intervalo de renovação. */
    public synchronized boolean acquireOrRenew() throws SQLException {
        long attemptStart = nanoClock.getAsLong();
        boolean acquired;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            acquired = dialect.acquireOrRenewLease(connection, holderId, durationMillis);
        } catch (SQLException e) {
            held = false;
            throw e;
        }
        held = acquired;
        if (acquired) {
            lastRenewNanos = attemptStart;
        }
        return acquired;
    }

    /** Expira o lease desta instância, se ela o detém, para que outra o assuma sem esperar a validade. */
    public synchronized void release() throws SQLException {
        boolean wasHeld = held;
        held = false;
        if (!wasHeld) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            dialect.releaseLease(connection, holderId);
        }
    }
}
