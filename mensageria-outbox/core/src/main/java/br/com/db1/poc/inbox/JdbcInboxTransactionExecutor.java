package br.com.db1.poc.inbox;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Transação local sobre JDBC puro, para uso sem framework: abre a conexão, desliga o autocommit, executa o
 * trabalho, confirma ou desfaz e fecha a conexão.
 */
public final class JdbcInboxTransactionExecutor implements InboxTransactionExecutor {

    private final DataSource dataSource;

    public JdbcInboxTransactionExecutor(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource é obrigatório");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void execute(TransactionWork work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (Throwable failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                restore(connection, autoCommit);
            }
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private static void restore(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            // a conexão volta ao pool ou é fechada em seguida; o estado de autocommit deixa de ser usado
        }
    }
}
