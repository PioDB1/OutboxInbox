package br.com.db1.poc.shared.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Definição de espaço em branco de {@link Texts}: a de {@code String.isBlank} e {@code String.strip} do Java 11. */
class TextsTest {

    private static final String EM_SPACE = "\u2003";
    private static final String NO_BREAK_SPACE = "\u00a0";

    @Test
    void isBlankReconheceVazioEEspacosEmBranco() {
        assertTrue(Texts.isBlank(""));
        assertTrue(Texts.isBlank("   "));
        assertTrue(Texts.isBlank(" \t\r\n\u000b\f"));
        assertTrue(Texts.isBlank(EM_SPACE + EM_SPACE));
        assertTrue(Texts.isBlank("\u2028\u2029\u001c\u001f"));
    }

    @Test
    void isBlankRejeitaTextoComCaractereVisivel() {
        assertFalse(Texts.isBlank("a"));
        assertFalse(Texts.isBlank(EM_SPACE + "a" + EM_SPACE));
        assertFalse(Texts.isBlank("\ud83d\ude00"));
    }

    @Test
    void espacoInsecivelNaoEEspacoEmBranco() {
        assertFalse(Texts.isBlank(NO_BREAK_SPACE));
        assertEquals(NO_BREAK_SPACE, Texts.strip(NO_BREAK_SPACE));
    }

    @Test
    void stripRemoveEspacosEmBrancoDasDuasPontas() {
        assertEquals("troca", Texts.strip(" " + EM_SPACE + "troca" + EM_SPACE + " "));
        assertEquals("a b", Texts.strip("\t a b \n"));
        assertEquals("a", Texts.strip("a"));
    }

    @Test
    void stripPreservaEspacosInternos() {
        assertEquals("a" + EM_SPACE + "b", Texts.strip(" a" + EM_SPACE + "b "));
    }

    @Test
    void stripDeTextoEmBrancoDevolveVazio() {
        assertEquals("", Texts.strip(""));
        assertEquals("", Texts.strip("  " + EM_SPACE + "  "));
    }

    @Test
    void stripPreservaParesSubstitutosNasBordas() {
        String emoji = "\ud83d\ude00";
        assertEquals(emoji, Texts.strip(" " + emoji + " "));
    }
}
