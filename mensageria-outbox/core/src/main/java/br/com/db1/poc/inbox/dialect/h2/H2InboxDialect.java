package br.com.db1.poc.inbox.dialect.h2;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Dialeto H2 da inbox, só com JDBC e {@code SQLState}: o driver é da aplicação hospedeira.
 *
 * <p>O padrão {@code CURRENT_TIMESTAMP} da coluna {@code consumed_at} grava o horário da zona da JVM; por isso a
 * inserção informa o instante em UTC.
 */
public final class H2InboxDialect implements InboxDialect {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String EXISTS = "SELECT 1 FROM inbox WHERE message_id = ?";
    private static final String INSERT = "INSERT INTO inbox (message_id, consumed_at) VALUES (?, ?)";

    @Override
    public boolean exists(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXISTS)) {
            statement.setObject(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Override
    public void insert(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, messageId);
            statement.setObject(2, LocalDateTime.now(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    @Override
    public boolean isDuplicateKey(SQLException e) {
        return UNIQUE_VIOLATION.equals(e.getSQLState());
    }

    @Override
    public String schemaScript() {
        return "h2/schema.sql";
    }
}
