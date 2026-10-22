package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.schema.InboxSchemaInstaller;
import br.com.db1.poc.shared.support.TestInfrastructure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;

/**
 * Base dos testes de integração da inbox com SQL Server: contêiner singleton do {@link TestInfrastructure}, tabela
 * {@code inbox} instalada uma vez pelo {@code InboxSchemaInstaller}, tabela de negócio {@code efeito} e tabelas
 * vazias a cada teste. Sem Docker em execução os testes são ignorados.
 */
public abstract class InboxSqlServerIT {

    protected static DataSource dataSource;
    private static boolean schemaInstalled;

    @BeforeAll
    static void startSqlServer() throws SQLException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker não está em execução");
        dataSource = preparedDataSource();
    }

    /** DataSource do SQL Server do {@link TestInfrastructure} com a tabela {@code inbox} e a {@code efeito} criadas. */
    public static synchronized DataSource preparedDataSource() throws SQLException {
        DataSource source = TestInfrastructure.dataSource();
        if (!schemaInstalled) {
            InboxSchemaInstaller.install(source, new SqlServerInboxDialect());
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("IF OBJECT_ID(N'efeito', N'U') IS NULL "
                        + "CREATE TABLE efeito ("
                        + "id INT IDENTITY(1,1) PRIMARY KEY, "
                        + "message_id UNIQUEIDENTIFIER NOT NULL, "
                        + "valor NVARCHAR(100) NOT NULL CONSTRAINT UX_efeito_valor UNIQUE)");
            }
            schemaInstalled = true;
        }
        return source;
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        clean(dataSource);
    }

    /** Esvazia as tabelas {@code inbox} e {@code efeito}. */
    public static void clean(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM inbox");
            statement.execute("DELETE FROM efeito");
        }
    }

    /** Linhas da tabela. */
    public static long count(DataSource source, String table) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Insere uma linha em {@code efeito} pela conexão da transação em curso. */
    public static void insertEffect(Connection connection, UUID messageId, String valor) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO efeito (message_id, valor) VALUES (?, ?)")) {
            statement.setString(1, messageId.toString());
            statement.setString(2, valor);
            statement.executeUpdate();
        }
    }
}
