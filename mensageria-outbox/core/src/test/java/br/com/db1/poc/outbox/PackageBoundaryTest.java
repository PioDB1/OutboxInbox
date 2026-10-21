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
    private static final Path BASE = SOURCES.resolve(Paths.get("br", "com", "db1", "poc"));
    private static final List<Path> RABBIT_PACKAGES = Arrays.asList(
            BASE.resolve(Paths.get("shared", "rabbit")),
            BASE.resolve(Paths.get("outbox", "rabbit")),
            BASE.resolve(Paths.get("inbox", "rabbit")));
    private static final List<String> JDK_INTERNAL_PREFIXES = Arrays.asList("sun.", "com.sun.", "jdk.internal.");

    /**
     * TC-15, TC-16 e TC-17: nenhum fonte importa Spring; {@code com.rabbitmq} só aparece nos pacotes {@code rabbit} de
     * {@code shared}, {@code outbox} e {@code inbox}; {@code shared} não importa {@code outbox} nem {@code inbox}, e
     * {@code outbox} e {@code inbox} não importam uma à outra.
     */
    @Test
    void fronteiraDePacotes() throws IOException {
        List<String> violations = new ArrayList<>();
        int sourcesRead = 0;
        for (Path file : mainSources()) {
            sourcesRead++;
            violations.addAll(boundaryViolations(file, importsOf(file)));
        }

        assertFalse(sourcesRead == 0, "nenhum fonte encontrado em " + SOURCES.toAbsolutePath());
        assertEquals(Collections.emptyList(), violations);
    }

    @Test
    void sharedNaoImportaOutboxNemInbox() {
        Path file = BASE.resolve(Paths.get("shared", "envelope", "Envelope.java"));

        assertEquals(1, boundaryViolations(file, imports("br.com.db1.poc.outbox.write.OutboxWriter")).size());
        assertEquals(1, boundaryViolations(file, imports("br.com.db1.poc.inbox.Inbox")).size());
        assertEquals(1, boundaryViolations(file, staticImports("br.com.db1.poc.outbox.write.OutboxWriter.write"))
                .size());
        assertEquals(Collections.emptyList(),
                boundaryViolations(file, imports("br.com.db1.poc.shared.text.Texts", "java.util.UUID")));
    }

    @Test
    void outboxEInboxNaoImportamUmaDaOutra() {
        Path outbox = BASE.resolve(Paths.get("outbox", "write", "OutboxWriter.java"));
        Path inbox = BASE.resolve(Paths.get("inbox", "consume", "Consumer.java"));

        assertEquals(1, boundaryViolations(outbox, imports("br.com.db1.poc.inbox.Inbox")).size());
        assertEquals(1, boundaryViolations(inbox, imports("br.com.db1.poc.outbox.write.OutboxWriter")).size());
        assertEquals(Collections.emptyList(),
                boundaryViolations(outbox, imports("br.com.db1.poc.shared.envelope.Envelope")));
        assertEquals(Collections.emptyList(),
                boundaryViolations(inbox, imports("br.com.db1.poc.shared.envelope.Envelope")));
    }

    @Test
    void rabbitmqSoNosPacotesRabbitAceitos() {
        String channel = "com.rabbitmq.client.Channel";

        assertEquals(1, boundaryViolations(BASE.resolve(Paths.get("shared", "envelope", "Envelope.java")),
                imports(channel)).size());
        assertEquals(1, boundaryViolations(BASE.resolve(Paths.get("outbox", "write", "OutboxWriter.java")),
                imports(channel)).size());
        for (Path rabbit : RABBIT_PACKAGES) {
            assertEquals(Collections.emptyList(), boundaryViolations(rabbit.resolve("Qualquer.java"), imports(channel)));
        }
    }

    @Test
    void springProibidoEmTodoOMain() {
        String bean = "org.springframework.context.annotation.Bean";

        for (String layer : Arrays.asList("shared", "outbox", "inbox")) {
            assertEquals(1, boundaryViolations(BASE.resolve(Paths.get(layer, "rabbit", "Qualquer.java")),
                    imports(bean)).size());
        }
    }

    /** Violações das regras de fronteira para um fonte e os seus {@code import}; cada uma cita o arquivo e o import. */
    private static List<String> boundaryViolations(Path file, List<String> imports) {
        boolean inShared = file.startsWith(BASE.resolve("shared"));
        boolean inOutbox = file.startsWith(BASE.resolve("outbox"));
        boolean inInbox = file.startsWith(BASE.resolve("inbox"));
        boolean inRabbitPackage = RABBIT_PACKAGES.stream().anyMatch(file::startsWith);
        List<String> violations = new ArrayList<>();
        for (String statement : imports) {
            if (statement.contains(" org.springframework")) {
                violations.add(file + ": " + statement + " (Spring é proibido no core)");
            }
            if (statement.contains(" com.rabbitmq") && !inRabbitPackage) {
                violations.add(file + ": " + statement + " (com.rabbitmq só nos pacotes rabbit)");
            }
            if ((inShared || inInbox) && statement.contains(" br.com.db1.poc.outbox.")) {
                violations.add(file + ": " + statement + " (esta camada não depende de outbox)");
            }
            if ((inShared || inOutbox) && statement.contains(" br.com.db1.poc.inbox.")) {
                violations.add(file + ": " + statement + " (esta camada não depende de inbox)");
            }
        }
        return violations;
    }

    private static List<String> imports(String... types) {
        return Arrays.stream(types).map(type -> "import " + type + ";").collect(Collectors.toList());
    }

    private static List<String> staticImports(String... members) {
        return Arrays.stream(members).map(member -> "import static " + member + ";").collect(Collectors.toList());
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
