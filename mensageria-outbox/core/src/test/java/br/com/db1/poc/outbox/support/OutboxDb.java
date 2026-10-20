package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.relay.RelayStrategyType;
import br.com.db1.poc.outbox.write.OutboxWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Acesso direto ao banco de teste: inserções, transações abertas à mão e leitura do estado da outbox. */
public final class OutboxDb {

    private final DataSource dataSource;
    private final OutboxWriter writer = new OutboxWriter(new SqlServerOutboxDialect());

    public OutboxDb(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Insere o envelope em uma transação própria, confirmada; devolve o {@code seq}. */
    public long insert(Envelope envelope) {
        try (Transaction transaction = open()) {
            long seq = transaction.insert(envelope);
            transaction.commit();
            return seq;
        }
    }

    /** Abre uma transação que a chamada controla (commit ou rollback), para simular transações concorrentes. */
    public Transaction open() {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new Transaction(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long count(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long outboxCount() {
        return count("SELECT COUNT(*) FROM outbox");
    }

    public boolean exists(UUID messageId) {
        return count("SELECT COUNT(*) FROM outbox WHERE message_id = ?", messageId.toString()) > 0;
    }

    /** Linha enviada: {@code sent_at} preenchido (claim) ou {@code rv <= last_rv} (ponto de leitura). */
    public boolean isSent(RelayStrategyType strategy, UUID messageId) {
        switch (strategy) {
            case CLAIM:
                return count("SELECT COUNT(*) FROM outbox WHERE message_id = ? AND sent_at IS NOT NULL",
                        messageId.toString()) > 0;
            case READ_POINT:
                return count("SELECT COUNT(*) FROM outbox o\n"
                        + "  JOIN outbox_read_point p ON p.relay_name = 'relay'\n"
                        + " WHERE o.message_id = ? AND o.rv <= p.last_rv", messageId.toString()) > 0;
            default:
                throw new IllegalStateException("Estratégia não tratada: " + strategy);
        }
    }

    /** Linhas pendentes: {@code sent_at IS NULL} (claim) ou {@code rv > last_rv} (ponto de leitura). */
    public long pendingCount(RelayStrategyType strategy) {
        switch (strategy) {
            case CLAIM:
                return count("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL");
            case READ_POINT:
                return count("SELECT COUNT(*) FROM outbox\n"
                        + " WHERE rv > COALESCE((SELECT last_rv FROM outbox_read_point WHERE relay_name = 'relay'),\n"
                        + "                     0x0000000000000000)");
            default:
                throw new IllegalStateException("Estratégia não tratada: " + strategy);
        }
    }

    /** Ponto de leitura gravado; oito bytes zero quando não há linha em {@code outbox_read_point}. */
    public byte[] readPoint() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT last_rv FROM outbox_read_point WHERE relay_name = 'relay'");
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getBytes(1) : new byte[8];
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code rv} da linha do {@code messageId}. */
    public byte[] rvOf(UUID messageId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT rv FROM outbox WHERE message_id = ?")) {
            statement.setString(1, messageId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBytes(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Detentor do lease, ou {@code null} quando {@code outbox_lease} está vazia. */
    public String leaseHolder() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT holder_id FROM outbox_lease");
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Expiração do lease, em milissegundos desde 2000-01-01 no relógio do banco. */
    public long leaseExpiresAtMillis() {
        return count("SELECT DATEDIFF_BIG(MILLISECOND, '2000-01-01', expires_at) FROM outbox_lease");
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    /** Transação aberta pelo teste sobre uma conexão própria. */
    public final class Transaction implements AutoCloseable {

        private final Connection connection;
        private boolean finished;

        private Transaction(Connection connection) {
            this.connection = connection;
        }

        public Connection connection() {
            return connection;
        }

        public long insert(Envelope envelope) {
            try {
                return writer.insert(connection, envelope);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        public void commit() {
            try {
                connection.commit();
                finished = true;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        public void rollback() {
            try {
                connection.rollback();
                finished = true;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            try {
                if (!finished) {
                    connection.rollback();
                }
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
