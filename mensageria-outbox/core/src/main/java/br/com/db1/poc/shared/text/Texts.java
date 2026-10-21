package br.com.db1.poc.shared.text;

/**
 * Operações de texto cuja definição de espaço em branco é a de {@link Character#isWhitespace(int)}, a mesma de
 * {@code String.isBlank} e {@code String.strip} do Java 11. Uso interno do módulo; não faz parte da API.
 */
public final class Texts {

    private Texts() {
    }

    /** Indica se o texto é vazio ou formado só por espaços em branco. */
    public static boolean isBlank(String text) {
        return indexOfFirstNonWhitespace(text) == text.length();
    }

    /** Remove os espaços em branco do início e do fim do texto. */
    public static String strip(String text) {
        int start = indexOfFirstNonWhitespace(text);
        if (start == text.length()) {
            return "";
        }
        int end = text.length();
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return text.substring(start, end);
    }

    private static int indexOfFirstNonWhitespace(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }
}
