package br.com.db1.poc.outbox.support;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.rabbitmq.client.ConnectionFactory;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.util.UUID;
import javax.sql.DataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contêineres singleton por JVM de teste: SQL Server, e RabbitMQ com Toxiproxy numa rede compartilhada. As
 * credenciais vêm das variáveis de ambiente {@code MSSQL_SA_PASSWORD}, {@code RABBITMQ_DEFAULT_USER} e
 * {@code RABBITMQ_DEFAULT_PASS} quando definidas; do contrário são geradas efêmeras.
 */
public final class TestInfrastructure {

    private static final int PROXY_PORT = 8666;
    private static final int RABBITMQ_PORT = 5672;

    private static MSSQLServerContainer sqlServer;
    private static Broker broker;

    private TestInfrastructure() {
    }

    /** RabbitMQ e Toxiproxy; o proxy {@code rabbitmq} encaminha para o broker pela rede compartilhada. */
    public record Broker(RabbitMQContainer rabbit, ToxiproxyContainer toxiproxy, ToxiproxyClient client,
            Proxy proxy) {

        /** Fábrica que conecta direto no RabbitMQ, sem passar pelo Toxiproxy (inspeção dos testes). */
        public ConnectionFactory directFactory() {
            return factory(rabbit.getHost(), rabbit.getAmqpPort());
        }

        /** Fábrica que conecta pelo Toxiproxy, sujeita aos toxics, para o publicador do relay. */
        public ConnectionFactory proxiedFactory() {
            ConnectionFactory factory = factory(toxiproxy.getHost(), toxiproxy.getMappedPort(PROXY_PORT));
            factory.setConnectionTimeout(2_000);
            factory.setHandshakeTimeout(2_000);
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(500);
            return factory;
        }

        private ConnectionFactory factory(String host, int port) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(rabbit.getAdminUsername());
            factory.setPassword(rabbit.getAdminPassword());
            return factory;
        }
    }

    public static synchronized MSSQLServerContainer sqlServer() {
        if (sqlServer == null) {
            MSSQLServerContainer container = new MSSQLServerContainer(DockerImageName.parse(Images.SQL_SERVER))
                    .acceptLicense()
                    .withPassword(setting("MSSQL_SA_PASSWORD", "Aa1!" + randomSecret()));
            container.start();
            sqlServer = container;
        }
        return sqlServer;
    }

    public static DataSource dataSource() {
        MSSQLServerContainer container = sqlServer();
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setServerName(container.getHost());
        dataSource.setPortNumber(container.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT));
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setEncrypt("false");
        dataSource.setTrustServerCertificate(true);
        return dataSource;
    }

    public static synchronized Broker broker() throws IOException {
        if (broker == null) {
            Network network = Network.newNetwork();
            RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse(Images.RABBITMQ))
                    .withAdminUser(setting("RABBITMQ_DEFAULT_USER", "outbox-test"))
                    .withAdminPassword(setting("RABBITMQ_DEFAULT_PASS", randomSecret()))
                    .withNetwork(network)
                    .withNetworkAliases("rabbitmq");
            ToxiproxyContainer toxiproxy = new ToxiproxyContainer(DockerImageName.parse(Images.TOXIPROXY))
                    .withNetwork(network);
            rabbit.start();
            toxiproxy.start();
            ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
            Proxy proxy = client.createProxy("rabbitmq", "0.0.0.0:" + PROXY_PORT, "rabbitmq:" + RABBITMQ_PORT);
            broker = new Broker(rabbit, toxiproxy, client, proxy);
        }
        return broker;
    }

    private static String setting(String environmentVariable, String fallback) {
        String value = System.getenv(environmentVariable);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String randomSecret() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
