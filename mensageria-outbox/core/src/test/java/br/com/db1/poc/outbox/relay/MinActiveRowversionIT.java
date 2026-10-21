package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.support.OutboxDb;
import br.com.db1.poc.outbox.support.SqlServerIT;
import br.com.db1.poc.shared.support.Images;
import br.com.db1.poc.shared.support.TestEnvelopes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * TC-25: registra a semântica de {@code MIN_ACTIVE_ROWVERSION()} na imagem fixada. Os valores observados são
 * gravados em {@code target/min-active-rowversion-observations.txt} para o ADR {@code 0001}.
 */
class MinActiveRowversionIT extends SqlServerIT {

    private static final Path OBSERVATIONS = Paths.get("target", "min-active-rowversion-observations.txt");

    @Test
    void semanticaDeMinActiveRowversion() throws SQLException, IOException {
        long idleDbts = dbts();
        long idleMinActive = minActive();
        assertEquals(idleDbts + 1, idleMinActive, "sem transações abertas o valor é @@DBTS + 1");

        long openMinActive;
        long insertedRv;
        long afterCommitMinActive;
        try (OutboxDb.Transaction transaction = db.open()) {
            long seq = transaction.insert(TestEnvelopes.of("agg-1"));
            insertedRv = rvOf(transaction.connection(), seq);
            openMinActive = minActive();
            assertTrue(openMinActive <= insertedRv, "com a transação aberta o valor é menor ou igual ao rv inserido");
            transaction.commit();
        }
        afterCommitMinActive = minActive();
        assertTrue(afterCommitMinActive > insertedRv, "depois do commit o valor avança acima do rv inserido");

        String report = String.format("imagem=%s\n"
                + "@@DBTS sem transações abertas=%d\n"
                + "MIN_ACTIVE_ROWVERSION() sem transações abertas=%d\n"
                + "rv da linha inserida=%d\n"
                + "MIN_ACTIVE_ROWVERSION() com a transação aberta=%d\n"
                + "MIN_ACTIVE_ROWVERSION() depois do commit=%d\n",
                Images.SQL_SERVER, idleDbts, idleMinActive, insertedRv, openMinActive, afterCommitMinActive);
        Files.createDirectories(OBSERVATIONS.getParent());
        Files.write(OBSERVATIONS, report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
    }

    private long dbts() throws SQLException {
        return scalar("SELECT @@DBTS", null);
    }

    private long minActive() throws SQLException {
        return scalar("SELECT MIN_ACTIVE_ROWVERSION()", null);
    }

    private static long rvOf(Connection connection, long seq) throws SQLException {
        return scalar(connection, "SELECT rv FROM outbox WHERE seq = ?", seq);
    }

    private long scalar(String sql, Long parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return scalar(connection, sql, parameter);
        }
    }

    private static long scalar(Connection connection, String sql, Long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setLong(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return ByteBuffer.wrap(rs.getBytes(1)).getLong();
            }
        }
    }
}
