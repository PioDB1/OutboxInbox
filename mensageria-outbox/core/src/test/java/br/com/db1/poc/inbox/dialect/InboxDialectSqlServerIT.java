package br.com.db1.poc.inbox.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.schema.InboxSchemaInstaller;
import br.com.db1.poc.inbox.support.InboxSqlServerIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxDialectSqlServerIT extends InboxSqlServerIT {

    private final SqlServerInboxDialect dialect = new SqlServerInboxDialect();

    /** TC-27: instalação repetível; {@code message_id} {@code uniqueidentifier} PK e {@code consumed_at} {@code datetime2}. */
    @Test
    void esquemaIdempotenteComAEstruturaDoModelo() throws SQLException {
        InboxSchemaInstaller.install(dataSource, dialect);
        InboxSchemaInstaller.install(dataSource, dialect);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("uniqueidentifier", columnType(connection, "message_id"));
            assertEquals("datetime2", columnType(connection, "consumed_at"));
            assertEquals(0, nullable(connection, "message_id"));
            assertEquals(0, nullable(connection, "consumed_at"));
            assertEquals("(sysutcdatetime())", defaultOf(connection, "consumed_at"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys.key_constraints"
                    + " WHERE name = 'PK_inbox' AND type = 'PK' AND parent_object_id = OBJECT_ID(N'inbox')"));
        }
    }

    /** TC-28 (SQL Server): a segunda inserção do mesmo {@code message_id} é o erro 2627, reconhecido. */
    @Test
    void violacaoDeChavePrimariaEReconhecida() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(dialect.exists(connection, id));
            dialect.insert(connection, id);
            assertTrue(dialect.exists(connection, id));
            assertFalse(dialect.exists(connection, UUID.randomUUID()));

            SQLException duplicate = assertThrows(SQLException.class, () -> dialect.insert(connection, id));

            assertEquals(2627, duplicate.getErrorCode());
            assertTrue(dialect.isDuplicateKey(duplicate));
        }
    }

    @Test
    void outroErroNaoEDuplicata() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            SQLException other = assertThrows(SQLException.class,
                    () -> statement.execute("SELECT 1 FROM tabela_inexistente"));

            assertFalse(dialect.isDuplicateKey(other));
        }
    }

    @Test
    void consumedAtRecebeOPadraoEmUtcDoBanco() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            dialect.insert(connection, id);

            long secondsFromUtcNow = scalar(connection,
                    "SELECT ABS(DATEDIFF(SECOND, consumed_at, SYSUTCDATETIME())) FROM inbox WHERE message_id = '"
                            + id + "'");

            assertTrue(secondsFromUtcNow < 60, "diferença de " + secondsFromUtcNow + " s");
        }
    }

    private static String columnType(Connection connection, String column) throws SQLException {
        return text(connection, "SELECT t.name FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id"
                + " WHERE c.object_id = OBJECT_ID(N'inbox') AND c.name = ?", column);
    }

    private static long nullable(Connection connection, String column) throws SQLException {
        return scalar(connection, "SELECT CAST(is_nullable AS INT) FROM sys.columns"
                + " WHERE object_id = OBJECT_ID(N'inbox') AND name = '" + column + "'");
    }

    private static String defaultOf(Connection connection, String column) throws SQLException {
        return text(connection, "SELECT d.definition FROM sys.default_constraints d JOIN sys.columns c"
                + " ON c.object_id = d.parent_object_id AND c.column_id = d.parent_column_id"
                + " WHERE d.parent_object_id = OBJECT_ID(N'inbox') AND c.name = ?", column);
    }

    private static String text(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                return rows.getString(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }
}
