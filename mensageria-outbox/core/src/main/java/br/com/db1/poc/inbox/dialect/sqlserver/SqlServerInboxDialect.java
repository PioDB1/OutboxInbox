package br.com.db1.poc.inbox.dialect.sqlserver;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.shared.dialect.SqlServerErrors;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Dialeto SQL Server da inbox. Os UUID trafegam como texto; {@code consumed_at} recebe o padrão da coluna
 * ({@code SYSUTCDATETIME()}).
 */
public final class SqlServerInboxDialect implements InboxDialect {

    private static final String EXISTS = "SELECT 1 FROM inbox WHERE message_id = ?";
    private static final String INSERT = "INSERT INTO inbox (message_id) VALUES (?)";

    @Override
    public boolean exists(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXISTS)) {
            statement.setString(1, messageId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Override
    public void insert(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, messageId.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean isDuplicateKey(SQLException e) {
        return SqlServerErrors.isDuplicateKey(e);
    }

    @Override
    public String schemaScript() {
        return "sqlserver/schema.sql";
    }
}
