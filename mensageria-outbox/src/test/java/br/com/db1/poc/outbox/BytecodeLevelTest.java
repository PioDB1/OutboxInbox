package br.com.db1.poc.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** TC-2: todo arquivo {@code .class} do módulo, principal e de teste, tem a versão principal 52 (Java 8). */
class BytecodeLevelTest {

    private static final int JAVA_8_MAJOR_VERSION = 52;
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    @Test
    void classesPrincipaisTemVersaoPrincipal52() throws IOException {
        assertMajorVersion(Paths.get("target", "classes"));
    }

    @Test
    void classesDeTesteTemVersaoPrincipal52() throws IOException {
        assertMajorVersion(Paths.get("target", "test-classes"));
    }

    private static void assertMajorVersion(Path directory) throws IOException {
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(directory)) {
            classFiles = files.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList());
        }
        assertFalse(classFiles.isEmpty(), "nenhum .class encontrado em " + directory.toAbsolutePath());

        List<String> outOfLevel = new ArrayList<>();
        for (Path classFile : classFiles) {
            int major = majorVersion(classFile);
            if (major != JAVA_8_MAJOR_VERSION) {
                outOfLevel.add(classFile + " (versão principal " + major + ")");
            }
        }
        assertEquals(Collections.emptyList(), outOfLevel, "arquivos .class fora do nível Java 8");
    }

    private static int majorVersion(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile); DataInputStream data = new DataInputStream(in)) {
            assertEquals(CLASS_FILE_MAGIC, data.readInt(), "cabeçalho inválido em " + classFile);
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
