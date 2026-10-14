package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.schema.OutboxSchemaInstaller;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;

/**
 * Base dos testes de integração com SQL Server: contêiner singleton, esquema instalado uma vez e tabelas vazias
 * a cada teste. Sem Docker em execução os testes são ignorados.
 */
public abstract class SqlServerIT {

    protected static DataSource dataSource;
    private static boolean schemaInstalled;

    protected OutboxDb db;

    @BeforeAll
    static void startSqlServer() throws SQLException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker não está em execução");
        dataSource = TestInfrastructure.dataSource();
        synchronized (SqlServerIT.class) {
            if (!schemaInstalled) {
                OutboxSchemaInstaller.install(dataSource);
                OutboxDb setup = new OutboxDb(dataSource);
                setup.execute("""
                        IF OBJECT_ID(N'negocio_teste', N'U') IS NULL
                            CREATE TABLE negocio_teste (id INT IDENTITY(1,1) PRIMARY KEY, nome NVARCHAR(50) NOT NULL)""");
                schemaInstalled = true;
            }
        }
    }

    @BeforeEach
    void cleanTables() {
        db = new OutboxDb(dataSource);
        db.execute("DELETE FROM outbox");
        db.execute("DELETE FROM outbox_lease");
        db.execute("DELETE FROM outbox_read_point");
        db.execute("DELETE FROM negocio_teste");
    }
}
