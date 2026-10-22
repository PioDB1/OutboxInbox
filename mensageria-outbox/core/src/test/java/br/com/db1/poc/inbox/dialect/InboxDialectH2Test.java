package br.com.db1.poc.inbox.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.support.H2Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxDialectH2Test {

    private final H2InboxDialect dialect = new H2InboxDialect();
    private final H2Db db = H2Db.memory();

    /** TC-28 (H2): a segunda inserção do mesmo {@code message_id} é violação de chave primária reconhecida. */
    @Test
    void violacaoDeChavePrimariaEReconhecida() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = db.dataSource().getConnection()) {
            assertFalse(dialect.exists(connection, id));
            dialect.insert(connection, id);
            assertTrue(dialect.exists(connection, id));
            assertFalse(dialect.exists(connection, UUID.randomUUID()));

            SQLException duplicate = assertThrows(SQLException.class, () -> dialect.insert(connection, id));

            assertEquals("23505", duplicate.getSQLState());
            assertTrue(dialect.isDuplicateKey(duplicate));
        }
    }

    @Test
    void outroErroNaoEDuplicata() throws SQLException {
        try (Connection connection = db.dataSource().getConnection(); Statement statement = connection.createStatement()) {
            SQLException other = assertThrows(SQLException.class, () -> statement.execute("SELECT 1 FROM tabela_inexistente"));

            assertFalse(dialect.isDuplicateKey(other));
        }
    }

    @Test
    void apontaOScriptDeEsquemaDoH2() {
        assertEquals("h2/schema.sql", dialect.schemaScript());
    }

    /** TC-29: {@code consumed_at} é o instante UTC, qualquer que seja a zona da JVM. */
    @Test
    void consumedAtEmUtcIndependenteDaZonaDaJvm() throws SQLException {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"America/Sao_Paulo", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                UUID id = UUID.randomUUID();
                try (Connection connection = db.dataSource().getConnection()) {
                    dialect.insert(connection, id);
                    LocalDateTime consumedAt = consumedAt(connection, id);

                    Duration difference = Duration.between(consumedAt, LocalDateTime.now(ZoneOffset.UTC)).abs();

                    assertTrue(difference.getSeconds() < 60, zone + ": " + difference);
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static LocalDateTime consumedAt(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT consumed_at FROM inbox WHERE message_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getObject(1, LocalDateTime.class);
            }
        }
    }
}
