package br.com.db1.poc.shared.dialect;

import java.sql.SQLException;

/** Reconhecimento de erros do SQL Server pelo código de erro do driver. */
public final class SqlServerErrors {

    private static final int DUPLICATE_KEY_CONSTRAINT = 2627;
    private static final int DUPLICATE_KEY_INDEX = 2601;

    private SqlServerErrors() {
    }

    /** {@code true} para violação de restrição de chave única (2627) ou de índice único (2601). */
    public static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == DUPLICATE_KEY_CONSTRAINT || e.getErrorCode() == DUPLICATE_KEY_INDEX;
    }
}
