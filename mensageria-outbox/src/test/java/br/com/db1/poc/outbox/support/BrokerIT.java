package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.rabbit.RabbitMessagePublisher;
import br.com.db1.poc.outbox.rabbit.RabbitTopology;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;

/**
 * Base dos testes de integração com SQL Server e RabbitMQ atrás do Toxiproxy. A cada teste: toxics removidos e
 * proxy habilitado, exchange declarada e uma fila quorum própria ligada à routing key {@link TestEnvelopes#TYPE}.
 */
public abstract class BrokerIT extends SqlServerIT {

    protected static final String EXCHANGE = "mensageria.outbox";

    protected static TestInfrastructure.Broker broker;

    protected String queue;
    private Connection inspection;
    private Channel inspectionChannel;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    /** Mensagem lida da fila de teste. */
    public record Received(byte[] body, Map<String, Object> headers, AMQP.BasicProperties properties) {

        public String header(String name) {
            return String.valueOf(headers.get(name));
        }
    }

    @BeforeAll
    static void startBroker() throws IOException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker não está em execução");
        broker = TestInfrastructure.broker();
    }

    @BeforeEach
    void resetBroker() throws IOException, TimeoutException {
        broker.client().reset();
        inspection = broker.directFactory().newConnection();
        inspectionChannel = inspection.createChannel();
        queue = "outbox-test-" + UUID.randomUUID();
        RabbitTopology.declareExchange(inspectionChannel, EXCHANGE);
        RabbitTopology.declareQuorumQueue(inspectionChannel, queue, EXCHANGE, TestEnvelopes.TYPE);
    }

    @AfterEach
    void closeBroker() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        closeables.clear();
        if (inspectionChannel != null && inspectionChannel.isOpen()) {
            inspectionChannel.queueDelete(queue);
        }
        if (inspection != null) {
            inspection.close();
        }
        broker.client().reset();
    }

    /** Registra o recurso para ser fechado ao fim do teste. */
    protected <T extends AutoCloseable> T closeAtEnd(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    /** Publicador RabbitMQ que passa pelo Toxiproxy. */
    protected MessagePublisher rabbitPublisher() {
        return closeAtEnd(new RabbitMessagePublisher(broker.proxiedFactory(), EXCHANGE, Duration.ofSeconds(3)));
    }

    protected Channel inspectionChannel() {
        return inspectionChannel;
    }

    /** Lê as mensagens disponíveis agora na fila de teste, removendo-as. */
    protected List<Received> readAvailable() {
        return readQueue(queue);
    }

    protected List<Received> readQueue(String name) {
        List<Received> messages = new ArrayList<>();
        try {
            GetResponse response;
            while ((response = inspectionChannel.basicGet(name, true)) != null) {
                messages.add(new Received(response.getBody(),
                        response.getProps().getHeaders(), response.getProps()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return messages;
    }

    /** Espera a chegada de {@code expected} mensagens à fila de teste e as devolve na ordem de chegada. */
    protected List<Received> awaitMessages(int expected, Duration timeout) {
        List<Received> all = new ArrayList<>();
        Await.until(timeout, expected + " mensagens na fila", () -> {
            all.addAll(readAvailable());
            return all.size() >= expected;
        });
        return all;
    }
}
