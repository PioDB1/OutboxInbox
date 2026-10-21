package br.com.db1.poc.shared.dialect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlServerErrorsTest {

    /** TC-7: 2627 (restrição de chave única) e 2601 (índice único) são violação de chave. */
    @Test
    void reconheceViolacaoDeChaveUnicaEDeIndiceUnico() {
        assertTrue(SqlServerErrors.isDuplicateKey(new SQLException("chave", "23000", 2627)));
        assertTrue(SqlServerErrors.isDuplicateKey(new SQLException("índice", "23000", 2601)));
    }

    /** TC-7: outros códigos, inclusive violação de chave estrangeira (547), não são violação de chave única. */
    @Test
    void outrosCodigosNaoSaoViolacaoDeChave() {
        assertFalse(SqlServerErrors.isDuplicateKey(new SQLException("fk", "23000", 547)));
        assertFalse(SqlServerErrors.isDuplicateKey(new SQLException("sem código")));
    }
}
