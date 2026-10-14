package br.com.db1.poc.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PackageBoundaryTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path RABBIT_PACKAGE = SOURCES.resolve(Path.of("br", "com", "db1", "poc", "outbox", "rabbit"));

    /** TC-14: nenhum fonte importa Spring, e somente o pacote {@code rabbit} importa {@code com.rabbitmq}. */
    @Test
    void fronteiraDePacotes() throws IOException {
        List<String> springImports = new ArrayList<>();
        List<String> rabbitImportsForaDoPacote = new ArrayList<>();
        int sourcesRead = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sourcesRead++;
                for (String line : Files.readAllLines(file)) {
                    String statement = line.strip();
                    if (!statement.startsWith("import ")) {
                        continue;
                    }
                    if (statement.contains(" org.springframework")) {
                        springImports.add(file + ": " + statement);
                    }
                    if (statement.contains(" com.rabbitmq") && !file.startsWith(RABBIT_PACKAGE)) {
                        rabbitImportsForaDoPacote.add(file + ": " + statement);
                    }
                }
            }
        }

        assertFalse(sourcesRead == 0, "nenhum fonte encontrado em " + SOURCES.toAbsolutePath());
        assertEquals(List.of(), springImports);
        assertEquals(List.of(), rabbitImportsForaDoPacote);
    }
}
