package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.support.H2Db;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdbcInboxTransactionExecutorTest {

    private final H2Db db = H2Db.memory();
    private final JdbcInboxTransactionExecutor executor = new JdbcInboxTransactionExecutor(db.dataSource());

    @Test
    void confirmaQuandoOTrabalhoRetorna() throws Exception {
        executor.execute(connection -> H2Db.insertEffect(connection, UUID.randomUUID(), "x"));

        assertEquals(1, db.count("efeito"));
    }

    @Test
    void desfazEPropagaQuandoOTrabalhoLanca() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> executor.execute(connection -> {
            H2Db.insertEffect(connection, UUID.randomUUID(), "x");
            throw new IllegalStateException("falha");
        }));

        assertEquals("falha", failure.getMessage());
        assertEquals(0, db.count("efeito"));
    }

    @Test
    void entregaConexaoSemAutocommitEFechaDepois() throws Exception {
        AtomicReference<Connection> used = new AtomicReference<>();

        executor.execute(connection -> {
            assertFalse(connection.getAutoCommit());
            used.set(connection);
        });

        assertTrue(used.get().isClosed());
    }

    @Test
    void recusaDataSourceNulo() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcInboxTransactionExecutor(null));
    }
}
