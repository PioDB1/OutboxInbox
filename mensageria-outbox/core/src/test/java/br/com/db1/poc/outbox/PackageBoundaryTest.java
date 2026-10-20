package br.com.db1.poc.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PackageBoundaryTest {

    private static final Path SOURCES = Paths.get("src", "main", "java");
    private static final Path RABBIT_PACKAGE =
            SOURCES.resolve(Paths.get("br", "com", "db1", "poc", "outbox", "rabbit"));
    private static final List<String> JDK_INTERNAL_PREFIXES = Arrays.asList("sun.", "com.sun.", "jdk.internal.");

    /** TC-14: nenhum fonte importa Spring, e somente o pacote {@code rabbit} importa {@code com.rabbitmq}. */
    @Test
    void fronteiraDePacotes() throws IOException {
        List<String> springImports = new ArrayList<>();
        List<String> rabbitImportsForaDoPacote = new ArrayList<>();
        int sourcesRead = 0;
        for (Path file : mainSources()) {
            sourcesRead++;
            for (String statement : importsOf(file)) {
                if (statement.contains(" org.springframework")) {
                    springImports.add(file + ": " + statement);
                }
                if (statement.contains(" com.rabbitmq") && !file.startsWith(RABBIT_PACKAGE)) {
                    rabbitImportsForaDoPacote.add(file + ": " + statement);
                }
            }
        }

        assertFalse(sourcesRead == 0, "nenhum fonte encontrado em " + SOURCES.toAbsolutePath());
        assertEquals(Collections.emptyList(), springImports);
        assertEquals(Collections.emptyList(), rabbitImportsForaDoPacote);
    }

    /** TC-10: nenhum fonte importa {@code sun.*}, {@code com.sun.*} ou {@code jdk.internal.*}. */
    @Test
    void semImportDeInternosDoJdk() throws IOException {
        List<String> internalImports = new ArrayList<>();
        for (Path file : mainSources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String statement = lines.get(i).trim();
                if (statement.startsWith("import ") && isJdkInternal(statement)) {
                    internalImports.add(file + ":" + (i + 1) + ": " + statement);
                }
            }
        }

        assertEquals(Collections.emptyList(), internalImports);
    }

    /** TC-10: nenhum fonte chama {@code setAccessible}. */
    @Test
    void semAcessoReflexivoComSetAccessible() throws IOException {
        List<String> reflectiveAccess = new ArrayList<>();
        for (Path file : mainSources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("setAccessible")) {
                    reflectiveAccess.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }

        assertEquals(Collections.emptyList(), reflectiveAccess);
    }

    private static boolean isJdkInternal(String importStatement) {
        String imported = importStatement.substring("import ".length()).trim();
        if (imported.startsWith("static ")) {
            imported = imported.substring("static ".length()).trim();
        }
        for (String prefix : JDK_INTERNAL_PREFIXES) {
            if (imported.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        List<String> imports = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String statement = line.trim();
            if (statement.startsWith("import ")) {
                imports.add(statement);
            }
        }
        return imports;
    }
}
