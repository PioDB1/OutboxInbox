package br.com.db1.poc.outbox.write;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.shared.envelope.Envelope;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Insere a linha da outbox na transação local aberta pela aplicação, junto do dado de negócio. A transação
 * (commit, rollback e fechamento da conexão) pertence à aplicação.
 */
public final class OutboxWriter {

    private final OutboxDialect dialect;

    public OutboxWriter(OutboxDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Insere o envelope na outbox.
     *
     * @return o {@code seq} atribuído pelo banco
     * @throws IllegalStateException se a conexão estiver em autocommit, pois a linha seria confirmada fora da
     *                               transação do dado de negócio
     */
    public long insert(Connection connection, Envelope envelope) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "A inserção na outbox exige a transação da aplicação: a conexão está em autocommit");
        }
        return dialect.insert(connection, envelope);
    }
}
