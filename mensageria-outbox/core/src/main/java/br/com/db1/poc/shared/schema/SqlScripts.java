package br.com.db1.poc.shared.schema;

import br.com.db1.poc.shared.text.Texts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Leitura de scripts SQL do classpath, divididos em lotes pelas linhas {@code GO}. */
public final class SqlScripts {

    private SqlScripts() {
    }

    /**
     * Lê o recurso UTF-8 e o divide nas linhas que contêm só {@code GO} (sem diferenciar maiúsculas de minúsculas e
     * ignorando espaços nas pontas); os lotes em branco são descartados.
     *
     * @param anchor   classe cujo pacote é a base do caminho relativo {@code resource}
     * @param resource caminho do script, relativo ao pacote de {@code anchor}
     * @return os lotes, na ordem do arquivo, sem as linhas separadoras
     * @throws IllegalStateException o recurso não existe
     */
    public static List<String> readBatches(Class<?> anchor, String resource) {
        String script;
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Script de esquema ausente: " + resource);
            }
            script = new String(readFully(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R", -1)) {
            if (Texts.strip(line).equalsIgnoreCase("GO")) {
                addBatch(batches, current);
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        addBatch(batches, current);
        return batches;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void addBatch(List<String> batches, StringBuilder batch) {
        if (!Texts.isBlank(batch.toString())) {
            batches.add(batch.toString());
        }
    }
}
