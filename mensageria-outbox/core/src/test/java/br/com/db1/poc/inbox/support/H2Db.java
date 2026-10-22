package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.schema.InboxSchemaInstaller;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Banco H2 de teste com a tabela {@code inbox} (instalada pelo {@code InboxSchemaInstaller}) e a tabela de
 * negócio {@code efeito}, gravada pelos tratadores de teste. Em memória ou em arquivo.
 */
public final class H2Db {

    private final JdbcDataSource dataSource = new JdbcDataSource();

    private H2Db(String url) {
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try {
            InboxSchemaInstaller.install(dataSource, new H2InboxDialect());
            execute("CREATE TABLE IF NOT EXISTS efeito (\n"
                    + "    id         BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
                    + "    message_id UUID         NOT NULL,\n"
                    + "    valor      VARCHAR(100) NOT NULL,\n"
                    + "    CONSTRAINT UX_efeito_valor UNIQUE (valor))");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Banco em memória, exclusivo do teste; vive enquanto a JVM viver. */
    public static H2Db memory() {
        return new H2Db("jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    /** Banco salvo em arquivo no diretório informado. */
    public static H2Db file(Path directory) {
        return new H2Db("jdbc:h2:file:" + directory.resolve("inbox-test").toAbsolutePath());
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public void execute(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long count(String table) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Insere uma linha em {@code efeito} pela conexão da transação em curso. */
    public static void insertEffect(Connection connection, UUID messageId, String valor) throws SQLException {
        try (java.sql.PreparedStatement statement =
                connection.prepareStatement("INSERT INTO efeito (message_id, valor) VALUES (?, ?)")) {
            statement.setObject(1, messageId);
            statement.setString(2, valor);
            statement.executeUpdate();
        }
    }
}
