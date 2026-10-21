package br.com.db1.poc.shared.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SqlScriptsTest {

    /** TC-6: os lotes vêm na ordem do arquivo, sem as linhas {@code GO} (qualquer caixa) nem os lotes em branco. */
    @Test
    void dividePeloSeparadorGoEDescartaLotesEmBranco() {
        List<String> batches = SqlScripts.readBatches(SqlScriptsTest.class, "lotes.sql");

        assertEquals(Arrays.asList("SELECT 1;", "SELECT 2;", "SELECT 3;", "SELECT 4;\nSELECT 5;"),
                batches.stream().map(String::trim).collect(Collectors.toList()));
    }

    /** TC-6: recurso inexistente falha citando o nome dele. */
    @Test
    void recursoAusenteFalhaComONomeDoRecurso() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SqlScripts.readBatches(SqlScriptsTest.class, "inexistente.sql"));

        assertTrue(failure.getMessage().contains("inexistente.sql"), failure.getMessage());
    }
}
