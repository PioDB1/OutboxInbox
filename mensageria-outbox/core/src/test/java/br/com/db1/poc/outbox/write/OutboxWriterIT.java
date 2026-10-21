package br.com.db1.poc.outbox.write;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.support.SqlServerIT;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.TestEnvelopes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxWriterIT extends SqlServerIT {

    private final OutboxWriter writer = new OutboxWriter(new SqlServerOutboxDialect());

    /** TC-1: a inserção confirmada grava a linha completa junto do dado de negócio. */
    @Test
    void insercaoConfirmadaGravaALinhaCompleta() throws SQLException {
        Envelope envelope = TestEnvelopes.of("pagamento-1", TestEnvelopes.TYPE,
                Instant.parse("2026-10-14T16:20:31.123456Z"), new byte[] {9, 8, 7});
        long seq;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertBusinessRow(connection);
            seq = writer.insert(connection, envelope);
            connection.commit();
        }

        assertEquals(1, db.outboxCount());
        assertEquals(1, db.count("SELECT COUNT(*) FROM negocio_teste"));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM outbox");
                ResultSet rs = statement.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(seq, rs.getLong("seq"));
            assertEquals(envelope.messageId(), UUID.fromString(rs.getString("message_id")));
            assertEquals(envelope.correlationId(), UUID.fromString(rs.getString("correlation_id")));
            assertEquals(envelope.idempotencyKey(), rs.getString("idempotency_key"));
            assertEquals(envelope.aggregateKey(), rs.getString("aggregate_key"));
            assertEquals(envelope.type(), rs.getString("type"));
            assertEquals(envelope.schemaVersion(), rs.getShort("schema_ver"));
            assertEquals(envelope.occurredAt(),
                    rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
            assertEquals(envelope.traceId(), rs.getString("trace_id"));
            assertArrayEquals(envelope.payload(), rs.getBytes("payload"));
            assertNotNull(rs.getObject("created_at"));
        }
    }

    /** TC-2: o {@code seq} cresce entre inserções consecutivas. */
    @Test
    void seqCresceEntreInsercoes() {
        long first = db.insert(TestEnvelopes.of("a"));
        long second = db.insert(TestEnvelopes.of("a"));

        assertTrue(second > first);
    }

    /** TC-3: o rollback não deixa linha na outbox nem dado de negócio. */
    @Test
    void rollbackNaoDeixaLinhaNemDadoDeNegocio() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertBusinessRow(connection);
            writer.insert(connection, TestEnvelopes.of("a"));
            connection.rollback();
        }

        assertEquals(0, db.outboxCount());
        assertEquals(0, db.count("SELECT COUNT(*) FROM negocio_teste"));
    }

    /** TC-6: a conexão em autocommit é rejeitada e nada é gravado. */
    @Test
    void conexaoEmAutocommitERejeitada() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);

            assertThrows(IllegalStateException.class, () -> writer.insert(connection, TestEnvelopes.of("a")));
        }

        assertEquals(0, db.outboxCount());
    }

    /** TC-8: o banco recusa {@code messageId} duplicado e mantém uma única linha. */
    @Test
    void messageIdDuplicadoERecusadoPeloBanco() {
        Envelope envelope = TestEnvelopes.of("a");
        db.insert(envelope);

        assertThrows(IllegalStateException.class, () -> db.insert(envelope));
        assertEquals(1, db.outboxCount());
    }

    private static void insertBusinessRow(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO negocio_teste (nome) VALUES (?)")) {
            statement.setString(1, "pagamento");
            statement.executeUpdate();
        }
    }
}
