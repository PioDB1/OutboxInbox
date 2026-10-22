package br.com.db1.poc.inbox.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Todo o SQL da inbox, isolado por dialeto de banco. As operações usam a conexão e a transação do chamador e não
 * fazem commit nem rollback.
 */
public interface InboxDialect {

    /** {@code true} se o {@code messageId} já consta na inbox. */
    boolean exists(Connection connection, UUID messageId) throws SQLException;

    /** Grava o {@code messageId} na inbox; a chave primária repetida lança uma exceção reconhecida por {@link #isDuplicateKey}. */
    void insert(Connection connection, UUID messageId) throws SQLException;

    /** {@code true} se a exceção é a violação da chave primária da inbox. */
    boolean isDuplicateKey(SQLException e);

    /**
     * Caminho do script de esquema do dialeto, relativo ao pacote de
     * {@link br.com.db1.poc.inbox.schema.InboxSchemaInstaller}.
     */
    String schemaScript();
}
