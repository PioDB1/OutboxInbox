package br.com.db1.poc.outbox.schema;

import br.com.db1.poc.outbox.internal.Texts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Instala o esquema SQL Server da outbox, do lease e do ponto de leitura; pode ser executado repetidamente. */
public final class OutboxSchemaInstaller {

    private static final String SCRIPT = "sqlserver/schema.sql";

    private OutboxSchemaInstaller() {
    }

    public static void install(DataSource dataSource) throws SQLException {
        List<String> batches = readBatches();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String batch : batches) {
                statement.execute(batch);
            }
        }
    }

    private static List<String> readBatches() {
        String script;
        try (InputStream in = OutboxSchemaInstaller.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("Script de esquema ausente: " + SCRIPT);
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
