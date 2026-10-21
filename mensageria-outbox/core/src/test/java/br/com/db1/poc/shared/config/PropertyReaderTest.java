package br.com.db1.poc.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PropertyReaderTest {

    private static PropertyReader reader(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty("teste." + keysAndValues[i], keysAndValues[i + 1]);
        }
        return new PropertyReader(properties, "teste.");
    }

    /** TC-4: valores válidos são lidos nas unidades corretas, e o texto perde os espaços das pontas. */
    @Test
    void leValoresValidos() {
        PropertyReader reader = reader("texto", "  valor  ", "inteiro", " 42 ", "decimal", "2.5",
                "ms", "250ms", "s", "5s", "m", "2m", "h", "1h", "d", "7d", "iso", "PT2S", "espaco", "10 s");

        assertEquals("valor", reader.text("texto", "padrao"));
        assertEquals(42, reader.integer("inteiro", 0));
        assertEquals(2.5, reader.decimal("decimal", 0.0));
        assertEquals(Duration.ofMillis(250), reader.duration("ms", "1s"));
        assertEquals(Duration.ofSeconds(5), reader.duration("s", "1s"));
        assertEquals(Duration.ofMinutes(2), reader.duration("m", "1s"));
        assertEquals(Duration.ofHours(1), reader.duration("h", "1s"));
        assertEquals(Duration.ofDays(7), reader.duration("d", "1s"));
        assertEquals(Duration.ofSeconds(2), reader.duration("iso", "1s"));
        assertEquals(Duration.ofSeconds(10), reader.duration("espaco", "1s"));
    }

    /** TC-4: chave ausente ou em branco devolve o valor padrão. */
    @Test
    void chaveAusenteOuEmBrancoDevolveOPadrao() {
        PropertyReader reader = reader("branco", "   ");

        assertEquals("padrao", reader.text("ausente", "padrao"));
        assertEquals("padrao", reader.text("branco", "padrao"));
        assertEquals(7, reader.integer("ausente", 7));
        assertEquals(7, reader.integer("branco", 7));
        assertEquals(1.5, reader.decimal("ausente", 1.5));
        assertEquals(Duration.ofMillis(500), reader.duration("ausente", "500ms"));
        assertEquals(Duration.ofMillis(500), reader.duration("branco", "500ms"));
    }

    /** TC-4: o padrão de texto pode ser nulo. */
    @Test
    void padraoDeTextoPodeSerNulo() {
        assertNull(reader().text("ausente", null));
    }

    /** TC-4: valor inválido lança a exceção com a chave prefixada e o valor lido. */
    @Test
    void valorInvalidoCitaAChaveComPrefixoEOValor() {
        PropertyReader reader = reader("inteiro", "abc", "decimal", "x", "duracao", "5 parsecs");

        IllegalArgumentException integer =
                assertThrows(IllegalArgumentException.class, () -> reader.integer("inteiro", 1));
        IllegalArgumentException decimal =
                assertThrows(IllegalArgumentException.class, () -> reader.decimal("decimal", 1.0));
        IllegalArgumentException duration =
                assertThrows(IllegalArgumentException.class, () -> reader.duration("duracao", "1s"));

        assertEquals("teste.inteiro deve ser um inteiro: 'abc'", integer.getMessage());
        assertEquals("teste.decimal deve ser um número: 'x'", decimal.getMessage());
        assertEquals("teste.duracao deve ser uma duração (ex.: 500ms, 5s, 2m, 1h, 7d ou ISO-8601): '5 parsecs'",
                duration.getMessage());
        assertTrue(integer.getCause() instanceof NumberFormatException);
    }
}
