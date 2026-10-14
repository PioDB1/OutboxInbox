package br.com.db1.poc.outbox.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.db1.poc.outbox.support.SqlServerIT;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class OutboxSchemaInstallerIT extends SqlServerIT {

    /** TC-9: a instalação repetida termina sem erro e o esquema fica com as tabelas e os índices do modelo. */
    @Test
    void instalacaoERepetivel() throws SQLException {
        OutboxSchemaInstaller.install(dataSource);
        OutboxSchemaInstaller.install(dataSource);

        for (String table : new String[] {"outbox", "outbox_lease", "outbox_read_point"}) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM sys.tables WHERE name = ?", table), table);
        }
        for (String index : new String[] {"PK_outbox", "IX_outbox_pendente", "IX_outbox_aggregate",
                "UX_outbox_rv", "UX_outbox_message_id"}) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID(N'outbox')",
                    index), index);
        }
        assertEquals(1, db.count(
                "SELECT COUNT(*) FROM sys.indexes WHERE name = 'IX_outbox_pendente' AND has_filter = 1"));

        db.insert(TestEnvelopes.of("a"));
        assertEquals(1, db.outboxCount());
    }
}
