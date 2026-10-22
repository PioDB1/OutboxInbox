package br.com.db1.poc.inbox.schema;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.shared.schema.SqlScripts;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/** Instala a tabela {@code inbox} com o script do dialeto; pode ser executado repetidamente. */
public final class InboxSchemaInstaller {

    private InboxSchemaInstaller() {
    }

    public static void install(DataSource dataSource, InboxDialect dialect) throws SQLException {
        List<String> batches = SqlScripts.readBatches(InboxSchemaInstaller.class, dialect.schemaScript());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String batch : batches) {
                statement.execute(batch);
            }
        }
    }
}
