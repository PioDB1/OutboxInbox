package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.InboxConfig;
import br.com.db1.poc.inbox.rabbit.RabbitMessageSource;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.rabbit.RabbitMessagePublisher;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.rabbit.RabbitTopology;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import br.com.db1.poc.shared.support.TestInfrastructure;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;

/**
 * Base dos testes de integração do consumo com RabbitMQ atrás do Toxiproxy. A cada teste: toxics removidos e proxy
 * habilitado, exchange declarada, fila principal quorum ligada à routing key {@link TestEnvelopes#TYPE} e DLQ
 * quorum própria. Sem Docker em execução os testes são ignorados.
 */
public abstract class InboxBrokerIT {

    protected static final String EXCHANGE = "mensageria.outbox";

    protected static TestInfrastructure.Broker broker;

    protected String queue;
    protected String deadLetterQueue;
    private Connection inspection;
    private Channel inspectionChannel;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    /** Mensagem lida de uma fila de teste. */
    public static final class Received {

        private final byte[] body;
        private final Map<String, Object> headers;

        Received(byte[] body, Map<String, Object> headers) {
            this.body = body;
            this.headers = headers;
        }

        public byte[] body() {
            return body;
        }

        public Object rawHeader(String name) {
            return headers.get(name);
        }

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
        queue = "inbox-test-" + UUID.randomUUID();
        deadLetterQueue = queue + ".dlq";
        RabbitTopology.declareExchange(inspectionChannel, EXCHANGE);
        RabbitTopology.declareQuorumQueue(inspectionChannel, queue, EXCHANGE, TestEnvelopes.TYPE);
        RabbitTopology.declareQuorumQueue(inspectionChannel, deadLetterQueue);
    }

    @AfterEach
    void closeBroker() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        closeables.clear();
        if (inspectionChannel != null && inspectionChannel.isOpen()) {
            inspectionChannel.queueDelete(queue);
            inspectionChannel.queueDelete(deadLetterQueue);
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

    /** Configuração de referência dos testes: backoff curto, as filas deste teste e os limites informados. */
    protected InboxConfig config(int maxAttempts, int maxInFlight, int workers) {
        return new InboxConfig(queue, deadLetterQueue, maxAttempts, Duration.ofMillis(20), 2.0,
                Duration.ofMillis(200), maxInFlight, workers, Duration.ofSeconds(5));
    }

    /** Porta de consumo direta ao RabbitMQ, sem passar pelo Toxiproxy. */
    protected RabbitMessageSource directSource() {
        return closeAtEnd(new RabbitMessageSource(broker.directFactory(), Duration.ofSeconds(5)));
    }

    /** Porta de consumo que passa pelo Toxiproxy, com a recuperação automática de conexão ligada. */
    protected RabbitMessageSource proxiedSource() {
        return closeAtEnd(new RabbitMessageSource(broker.proxiedFactory(), Duration.ofSeconds(5)));
    }

    /** Publica o envelope pelo {@code RabbitMessagePublisher} do {@code core}, com confirm. */
    protected void publish(Envelope envelope) throws PublishException {
        try (RabbitMessagePublisher publisher =
                new RabbitMessagePublisher(broker.directFactory(), EXCHANGE, Duration.ofSeconds(5))) {
            publisher.publish(PublishableMessage.from(envelope));
        }
    }

    /** Publica muitos envelopes em um só canal, com confirm a cada lote, no formato do {@code RabbitMessagePublisher}. */
    protected void publishAll(List<Envelope> envelopes) throws IOException, TimeoutException, InterruptedException {
        try (Connection connection = broker.directFactory().newConnection();
                Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            int pending = 0;
            for (Envelope envelope : envelopes) {
                AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                        .headers(SimulatedDelivery.of(envelope).headers())
                        .deliveryMode(2)
                        .contentType("application/octet-stream")
                        .build();
                channel.basicPublish(EXCHANGE, envelope.type(), true, properties, envelope.payload());
                if (++pending == 500) {
                    channel.waitForConfirmsOrDie(30_000);
                    pending = 0;
                }
            }
            channel.waitForConfirmsOrDie(30_000);
        }
    }

    /** Mensagens prontas agora na fila (sem as entregues e ainda sem ack). */
    protected long readyMessages(String name) throws IOException {
        return inspectionChannel.messageCount(name);
    }

    /** Lê as mensagens disponíveis agora na fila, removendo-as. */
    protected List<Received> readQueue(String name) throws IOException {
        List<Received> messages = new ArrayList<>();
        GetResponse response;
        while ((response = inspectionChannel.basicGet(name, true)) != null) {
            messages.add(new Received(response.getBody(), response.getProps().getHeaders()));
        }
        return messages;
    }

    /** Espera {@code expected} mensagens na fila e as devolve, removendo-as. */
    protected List<Received> awaitMessages(String name, int expected, Duration timeout) {
        List<Received> all = new ArrayList<>();
        Await.until(timeout, expected + " mensagens em " + name, () -> {
            try {
                all.addAll(readQueue(name));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return all.size() >= expected;
        });
        return all;
    }

    /** Mensagens entregues e sem ack segundo a API de gestão do broker; {@code -1} enquanto não há estatística. */
    protected long unacknowledged(String name) throws IOException {
        String json = managementGet("/api/queues/%2F/" + name);
        Matcher matcher = Pattern.compile("\"messages_unacknowledged\"\\s*:\\s*(\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    /** {@code prefetch_count} do consumidor da fila segundo a API de gestão; {@code -1} se não consta. */
    protected int consumerPrefetch(String name) throws IOException {
        String json = managementGet("/api/consumers/%2F");
        Matcher matcher = Pattern
                .compile("\"prefetch_count\"\\s*:\\s*(\\d+)\\s*,\\s*\"queue\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\""
                        + Pattern.quote(name) + "\"")
                .matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    protected ConnectionFactory directFactory() {
        return broker.directFactory();
    }

    private static String managementGet(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(broker.rabbit().getHttpUrl() + path)
                .openConnection();
        String credentials = broker.rabbit().getAdminUsername() + ":" + broker.rabbit().getAdminPassword();
        connection.setRequestProperty("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try (InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
