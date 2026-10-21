package br.com.db1.poc.outbox.schema;

import br.com.db1.poc.shared.schema.SqlScripts;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/** Instala o esquema SQL Server da outbox, do lease e do ponto de leitura; pode ser executado repetidamente. */
public final class OutboxSchemaInstaller {

    private static final String SCRIPT = "sqlserver/schema.sql";

    private OutboxSchemaInstaller() {
    }

    public static void install(DataSource dataSource) throws SQLException {
        List<String> batches = SqlScripts.readBatches(OutboxSchemaInstaller.class, SCRIPT);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String batch : batches) {
                statement.execute(batch);
            }
        }
    }
}
