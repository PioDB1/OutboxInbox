package br.com.db1.poc.outbox.dialect.sqlserver;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;
import br.com.db1.poc.shared.dialect.SqlServerErrors;
import br.com.db1.poc.shared.envelope.Envelope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dialeto SQL Server: dicas {@code UPDLOCK}/{@code READPAST}/{@code ROWLOCK} da estratégia claim,
 * {@code MIN_ACTIVE_ROWVERSION()} da estratégia de ponto de leitura e relógio do banco para lease e retenção.
 *
 * <p>Os UUID trafegam como texto e as datas como {@link LocalDateTime} em UTC, de modo que o resultado não
 * depende do fuso da JVM.
 */
public final class SqlServerOutboxDialect implements OutboxDialect {

    private static final String RELAY_NAME = "relay";

    private static final String COLUMNS =
            "seq, rv, message_id, correlation_id, idempotency_key, aggregate_key, type, schema_ver, occurred_at,"
                    + " trace_id, payload";

    private static final String INSERT =
            "INSERT INTO outbox (message_id, correlation_id, idempotency_key, aggregate_key, type, schema_ver,\n"
            + "                    occurred_at, trace_id, payload)\n"
            + "OUTPUT INSERTED.seq\n"
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String CLAIM_PENDING = "SELECT TOP (?) " + COLUMNS
            + " FROM outbox WITH (UPDLOCK, READPAST, ROWLOCK) WHERE sent_at IS NULL ORDER BY seq";

    private static final String PENDING_BETWEEN = "SELECT TOP (1) 1 FROM outbox WITH (READUNCOMMITTED)\n"
            + "WHERE aggregate_key = ? AND seq > ? AND seq < ? AND sent_at IS NULL";

    private static final String MARK_SENT = "UPDATE outbox SET sent_at = SYSUTCDATETIME() WHERE seq = ?";

    private static final String MIN_ACTIVE_ROWVERSION = "SELECT MIN_ACTIVE_ROWVERSION()";

    private static final String READ_POINT = "SELECT last_rv FROM outbox_read_point WHERE relay_name = ?";

    // A dica força a busca por faixa em UX_outbox_rv: uma varredura do índice clusterizado esbarraria nas linhas
    // ainda não confirmadas, cujos bloqueios a leitura aguardaria.
    private static final String READ_RANGE = "SELECT TOP (?) " + COLUMNS
            + " FROM outbox WITH (INDEX(UX_outbox_rv))"
            + " WHERE rv > CAST(? AS BINARY(8)) AND rv < CAST(? AS BINARY(8)) ORDER BY rv";

    private static final String ADVANCE_READ_POINT =
            "UPDATE outbox_read_point SET last_rv = CAST(? AS BINARY(8)), updated_at = SYSUTCDATETIME()\n"
            + "WHERE relay_name = ? AND last_rv < CAST(? AS BINARY(8))";

    private static final String READ_POINT_EXISTS = "SELECT 1 FROM outbox_read_point WHERE relay_name = ?";

    private static final String INSERT_READ_POINT =
            "INSERT INTO outbox_read_point (relay_name, last_rv) VALUES (?, CAST(? AS BINARY(8)))";

    // READPAST: a consulta de métricas não espera as linhas de transações abertas (inserções ainda não
    // confirmadas e linhas que a estratégia claim está marcando).
    private static final String PENDING_UNSENT =
            "SELECT COUNT_BIG(*), DATEDIFF_BIG(MILLISECOND, MIN(created_at), SYSUTCDATETIME())\n"
            + "  FROM outbox WITH (READPAST)\n"
            + " WHERE sent_at IS NULL";

    private static final String PENDING_AHEAD_OF_READ_POINT =
            "SELECT COUNT_BIG(*), DATEDIFF_BIG(MILLISECOND, MIN(created_at), SYSUTCDATETIME())\n"
            + "  FROM outbox WITH (READPAST)\n"
            + " WHERE rv > COALESCE((SELECT last_rv FROM outbox_read_point WHERE relay_name = ?), 0x0000000000000000)";

    private static final String RENEW_LEASE = "UPDATE outbox_lease\n"
            + "   SET holder_id = ?, expires_at = DATEADD(MILLISECOND, ?, SYSUTCDATETIME())\n"
            + " WHERE lease_name = ? AND (holder_id = ? OR expires_at < SYSUTCDATETIME())";

    private static final String INSERT_LEASE = "INSERT INTO outbox_lease (lease_name, holder_id, expires_at)\n"
            + "SELECT ?, ?, DATEADD(MILLISECOND, ?, SYSUTCDATETIME())\n"
            + " WHERE NOT EXISTS (SELECT 1 FROM outbox_lease WITH (UPDLOCK, HOLDLOCK) WHERE lease_name = ?)";

    private static final String RELEASE_LEASE =
            "UPDATE outbox_lease SET expires_at = DATEADD(MILLISECOND, -1, SYSUTCDATETIME())\n"
            + " WHERE lease_name = ? AND holder_id = ?";

    private static final String DELETE_SENT = "DELETE TOP (?) FROM outbox WITH (READPAST)\n"
            + " WHERE sent_at IS NOT NULL\n"
            + "   AND sent_at < DATEADD(SECOND, ?, DATEADD(MILLISECOND, ?, SYSUTCDATETIME()))";

    private static final String DELETE_BEHIND_READ_POINT =
            "DELETE TOP (?) FROM outbox WITH (READPAST, INDEX(UX_outbox_rv))\n"
            + " WHERE rv <= (SELECT last_rv FROM outbox_read_point WHERE relay_name = ?)\n"
            + "   AND created_at < DATEADD(SECOND, ?, DATEADD(MILLISECOND, ?, SYSUTCDATETIME()))";

    private static final byte[] ZERO_RV = new byte[8];

    @Override
    public long insert(Connection connection, Envelope envelope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, envelope.messageId().toString());
            statement.setString(2, envelope.correlationId().toString());
            statement.setString(3, envelope.idempotencyKey());
            statement.setString(4, envelope.aggregateKey());
            statement.setString(5, envelope.type());
            statement.setShort(6, envelope.schemaVersion());
            statement.setObject(7, toUtc(envelope.occurredAt()), Types.TIMESTAMP);
            statement.setString(8, envelope.traceId());
            statement.setBytes(9, envelope.payload());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Override
    public List<OutboxRow> claimPending(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_PENDING)) {
            statement.setInt(1, limit);
            return readRows(statement);
        }
    }

    @Override
    public boolean hasPendingBetween(Connection connection, String aggregateKey, long afterSeq, long beforeSeq)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PENDING_BETWEEN)) {
            statement.setString(1, aggregateKey);
            statement.setLong(2, afterSeq);
            statement.setLong(3, beforeSeq);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void markSent(Connection connection, List<Long> seqs) throws SQLException {
        if (seqs.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(MARK_SENT)) {
            for (long seq : seqs) {
                statement.setLong(1, seq);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Override
    public byte[] minActiveRowversion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MIN_ACTIVE_ROWVERSION);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getBytes(1);
        }
    }

    @Override
    public byte[] readPoint(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_POINT)) {
            statement.setString(1, RELAY_NAME);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : ZERO_RV.clone();
            }
        }
    }

    @Override
    public List<OutboxRow> readRange(Connection connection, byte[] afterExclusive, byte[] beforeExclusive,
            int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_RANGE)) {
            statement.setInt(1, limit);
            statement.setBytes(2, afterExclusive);
            statement.setBytes(3, beforeExclusive);
            return readRows(statement);
        }
    }

    @Override
    public void advanceReadPoint(Connection connection, byte[] rv) throws SQLException {
        if (updateReadPoint(connection, rv) > 0) {
            return;
        }
        if (readPointExists(connection)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_READ_POINT)) {
            statement.setString(1, RELAY_NAME);
            statement.setBytes(2, rv);
            statement.executeUpdate();
        } catch (SQLException e) {
            if (!SqlServerErrors.isDuplicateKey(e)) {
                throw e;
            }
            updateReadPoint(connection, rv);
        }
    }

    @Override
    public PendingSummary pendingUnsent(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PENDING_UNSENT)) {
            return readSummary(statement);
        }
    }

    @Override
    public PendingSummary pendingAheadOfReadPoint(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PENDING_AHEAD_OF_READ_POINT)) {
            statement.setString(1, RELAY_NAME);
            return readSummary(statement);
        }
    }

    @Override
    public boolean acquireOrRenewLease(Connection connection, String holderId, long durationMillis)
            throws SQLException {
        int duration = (int) Math.min(durationMillis, Integer.MAX_VALUE);
        try (PreparedStatement statement = connection.prepareStatement(RENEW_LEASE)) {
            statement.setString(1, holderId);
            statement.setInt(2, duration);
            statement.setString(3, RELAY_NAME);
            statement.setString(4, holderId);
            if (statement.executeUpdate() > 0) {
                return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_LEASE)) {
            statement.setString(1, RELAY_NAME);
            statement.setString(2, holderId);
            statement.setInt(3, duration);
            statement.setString(4, RELAY_NAME);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            if (SqlServerErrors.isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void releaseLease(Connection connection, String holderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_LEASE)) {
            statement.setString(1, RELAY_NAME);
            statement.setString(2, holderId);
            statement.executeUpdate();
        }
    }

    @Override
    public int deleteSentByTimestamp(Connection connection, long maxAgeMillis, int batchSize) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SENT)) {
            statement.setInt(1, batchSize);
            setNegativeAge(statement, 2, maxAgeMillis);
            return statement.executeUpdate();
        }
    }

    @Override
    public int deleteBehindReadPoint(Connection connection, long maxAgeMillis, int batchSize) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_BEHIND_READ_POINT)) {
            statement.setInt(1, batchSize);
            statement.setString(2, RELAY_NAME);
            setNegativeAge(statement, 3, maxAgeMillis);
            return statement.executeUpdate();
        }
    }

    private static int updateReadPoint(Connection connection, byte[] rv) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ADVANCE_READ_POINT)) {
            statement.setBytes(1, rv);
            statement.setString(2, RELAY_NAME);
            statement.setBytes(3, rv);
            return statement.executeUpdate();
        }
    }

    private static boolean readPointExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_POINT_EXISTS)) {
            statement.setString(1, RELAY_NAME);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Idade máxima como dois deslocamentos negativos (segundos e milissegundos), que cabem em {@code int}. */
    private static void setNegativeAge(PreparedStatement statement, int firstIndex, long maxAgeMillis)
            throws SQLException {
        statement.setInt(firstIndex, (int) -(maxAgeMillis / 1000));
        statement.setInt(firstIndex + 1, (int) -(maxAgeMillis % 1000));
    }

    private static PendingSummary readSummary(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            long count = rs.getLong(1);
            long ageMillis = rs.getLong(2);
            return new PendingSummary(count, rs.wasNull() ? 0 : Math.max(ageMillis, 0));
        }
    }

    private static List<OutboxRow> readRows(PreparedStatement statement) throws SQLException {
        List<OutboxRow> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Envelope envelope = new Envelope(
                        UUID.fromString(rs.getString("message_id")),
                        UUID.fromString(rs.getString("correlation_id")),
                        rs.getString("idempotency_key"),
                        rs.getString("aggregate_key"),
                        rs.getString("type"),
                        rs.getShort("schema_ver"),
                        rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getBytes("payload"),
                        rs.getString("trace_id"));
                rows.add(new OutboxRow(rs.getLong("seq"), rs.getBytes("rv"), envelope));
            }
        }
        return rows;
    }

    private static LocalDateTime toUtc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
