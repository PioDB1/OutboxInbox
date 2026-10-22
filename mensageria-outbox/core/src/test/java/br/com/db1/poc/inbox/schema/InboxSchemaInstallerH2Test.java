package br.com.db1.poc.inbox.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class InboxSchemaInstallerH2Test {

    private static DataSource emptyDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** TC-26: duas instalações concluem e deixam a tabela {@code inbox} da estrutura do modelo de dados. */
    @Test
    void instalacaoIdempotenteCriaATabelaInbox() throws SQLException {
        DataSource dataSource = emptyDatabase();

        InboxSchemaInstaller.install(dataSource, new H2InboxDialect());
        InboxSchemaInstaller.install(dataSource, new H2InboxDialect());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, "PUBLIC", "INBOX", null)) {
                assertTrue(columns.next());
                assertEquals("MESSAGE_ID", columns.getString("COLUMN_NAME"));
                assertEquals("UUID", columns.getString("TYPE_NAME"));
                assertEquals("NO", columns.getString("IS_NULLABLE"));
                assertTrue(columns.next());
                assertEquals("CONSUMED_AT", columns.getString("COLUMN_NAME"));
                assertEquals("TIMESTAMP", columns.getString("TYPE_NAME"));
                assertEquals("NO", columns.getString("IS_NULLABLE"));
                assertEquals("CURRENT_TIMESTAMP", columns.getString("COLUMN_DEF"));
            }
            try (ResultSet keys = metadata.getPrimaryKeys(null, "PUBLIC", "INBOX")) {
                assertTrue(keys.next());
                assertEquals("MESSAGE_ID", keys.getString("COLUMN_NAME"));
            }
        }
    }

    @Test
    void reinstalarPreservaAsLinhas() throws SQLException {
        DataSource dataSource = emptyDatabase();
        InboxSchemaInstaller.install(dataSource, new H2InboxDialect());
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            new H2InboxDialect().insert(connection, id);
        }

        InboxSchemaInstaller.install(dataSource, new H2InboxDialect());

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(new H2InboxDialect().exists(connection, id));
        }
    }
}
