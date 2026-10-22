package br.com.db1.poc.inbox.rabbit;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.inbox.MessageSource;
import br.com.db1.poc.inbox.StaleDeliveryException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador RabbitMQ da porta de consumo: consome a fila principal com ack manual e {@code basicQos}, e publica
 * na DLQ com confirm.
 *
 * <ul>
 *   <li>{@code start} confirma as duas filas com {@code queueDeclarePassive} (a aplicação as declara), aplica
 *       {@code basicQos(maxInFlight)} e registra o consumo com {@code autoAck=false}.
 *   <li>{@code ack} e {@code requeue} saem pelo canal que recebeu a entrega, com as operações do canal serializadas
 *       por um lock.
 *   <li>{@code deadLetter} publica no exchange padrão com a DLQ como routing key e {@code mandatory=true}, com o
 *       corpo e os headers originais mais {@code x-inbox-failure} e {@code x-inbox-attempts}, espera o confirm em
 *       um canal próprio e só então dá o ack da original. Falha, devolução ({@code basic.return}) ou timeout do
 *       confirm lançam {@link IOException} e a original permanece sem ack.
 * </ul>
 *
 * <p>O {@link ConnectionFactory} é o da aplicação, com a recuperação automática de conexão ligada. Depois de uma
 * queda do canal o broker devolve à fila as mensagens sem ack e as entrega de novo; uma entrega recebida antes da
 * queda não vale mais, e {@code ack}, {@code requeue} e {@code deadLetter} sobre ela lançam
 * {@link StaleDeliveryException}, porque um ack com a etiqueta de entrega de um canal anterior fecharia o canal.
 */
public final class RabbitMessageSource implements MessageSource {

    static final String HEADER_FAILURE = "x-inbox-failure";
    static final String HEADER_ATTEMPTS = "x-inbox-attempts";

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMessageSource.class);

    private final ConnectionFactory factory;
    private final long confirmTimeoutMillis;
    private final Object channelLock = new Object();
    private final AtomicInteger channelEpoch = new AtomicInteger();
    private final AtomicReference<String> returned = new AtomicReference<>();

    private Connection connection;
    private Channel consumeChannel;
    private Channel confirmChannel;
    private String deadLetterQueue;
    private boolean closed;

    public RabbitMessageSource(ConnectionFactory factory, Duration confirmTimeout) {
        this.factory = factory;
        this.confirmTimeoutMillis = confirmTimeout.toMillis();
    }

    @Override
    public void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery)
            throws IOException {
        synchronized (channelLock) {
            if (closed) {
                throw new IOException("A porta de consumo foi fechada");
            }
            if (connection != null) {
                throw new IOException("A porta de consumo já foi iniciada");
            }
            this.deadLetterQueue = deadLetterQueue;
            try {
                connection = factory.newConnection();
                requireQueue(queue);
                requireQueue(deadLetterQueue);
                Channel channel = connection.createChannel();
                channel.addShutdownListener(cause -> channelEpoch.incrementAndGet());
                channel.basicQos(maxInFlight);
                channel.basicConsume(queue, false,
                        (consumerTag, message) -> onDelivery.accept(new RabbitDelivery(message, channelEpoch.get())),
                        consumerTag -> LOG.warn("O broker cancelou o consumo da fila '{}'", queue));
                consumeChannel = channel;
            } catch (IOException | TimeoutException | ShutdownSignalException e) {
                closeQuietly();
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException("Broker inacessível: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void ack(Delivery delivery) throws IOException {
        RabbitDelivery message = (RabbitDelivery) delivery;
        synchronized (channelLock) {
            Channel channel = validChannel(message);
            try {
                channel.basicAck(message.deliveryTag, false);
            } catch (AlreadyClosedException e) {
                throw new StaleDeliveryException("Canal fechado antes do ack", e);
            }
        }
    }

    @Override
    public void requeue(Delivery delivery) throws IOException {
        RabbitDelivery message = (RabbitDelivery) delivery;
        synchronized (channelLock) {
            Channel channel = validChannel(message);
            try {
                channel.basicNack(message.deliveryTag, false, true);
            } catch (AlreadyClosedException e) {
                throw new StaleDeliveryException("Canal fechado antes da devolução", e);
            }
        }
    }

    @Override
    public void deadLetter(Delivery delivery, String failure, int attempts) throws IOException {
        RabbitDelivery message = (RabbitDelivery) delivery;
        synchronized (channelLock) {
            validChannel(message);
            publishToDeadLetterQueue(message, failure, attempts);
            try {
                consumeChannel.basicAck(message.deliveryTag, false);
            } catch (IOException | ShutdownSignalException e) {
                throw new StaleDeliveryException(
                        "Canal perdido entre a publicação na DLQ e o ack da original", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (channelLock) {
            closed = true;
            closeQuietly();
        }
    }

    private void publishToDeadLetterQueue(RabbitDelivery message, String failure, int attempts)
            throws IOException {
        Map<String, Object> headers = new HashMap<>(message.rawHeaders);
        headers.put(HEADER_FAILURE, failure);
        headers.put(HEADER_ATTEMPTS, attempts);
        AMQP.BasicProperties properties = message.properties.builder().headers(headers).deliveryMode(2).build();
        try {
            Channel channel = openConfirmChannel();
            returned.set(null);
            channel.basicPublish("", deadLetterQueue, true, properties, message.body);
            boolean confirmed = channel.waitForConfirms(confirmTimeoutMillis);
            if (!confirmed) {
                throw new IOException("Confirm negativo do broker para a DLQ '" + deadLetterQueue + "'");
            }
            String devolucao = returned.get();
            if (devolucao != null) {
                throw new IOException("Mensagem devolvida pelo broker (" + devolucao + ") para a DLQ '"
                        + deadLetterQueue + "'");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discardConfirmChannel();
            throw new IOException("Publicação na DLQ interrompida", e);
        } catch (TimeoutException e) {
            discardConfirmChannel();
            throw new IOException("Confirm da DLQ não recebido em " + confirmTimeoutMillis + " ms", e);
        } catch (ShutdownSignalException e) {
            discardConfirmChannel();
            throw new IOException("Falha ao publicar na DLQ: " + e.getMessage(), e);
        } catch (IOException e) {
            discardConfirmChannel();
            throw e;
        }
    }

    private Channel openConfirmChannel() throws IOException {
        if (confirmChannel == null || !confirmChannel.isOpen()) {
            Channel channel = connection.createChannel();
            channel.confirmSelect();
            channel.addReturnListener((replyCode, replyText, exchange, routingKey, props, body) ->
                    returned.set(replyCode + " " + replyText));
            confirmChannel = channel;
        }
        return confirmChannel;
    }

    private void discardConfirmChannel() {
        if (confirmChannel != null) {
            try {
                if (confirmChannel.isOpen()) {
                    confirmChannel.close();
                }
            } catch (IOException | TimeoutException | ShutdownSignalException e) {
                // o canal já está fechado ou inutilizável
            }
            confirmChannel = null;
        }
    }

    private Channel validChannel(RabbitDelivery message) throws StaleDeliveryException {
        Channel channel = consumeChannel;
        if (channel == null || message.epoch != channelEpoch.get() || !channel.isOpen()) {
            throw new StaleDeliveryException("A entrega pertence a um canal que não existe mais");
        }
        return channel;
    }

    private void requireQueue(String queue) throws IOException, TimeoutException {
        Channel channel = connection.createChannel();
        try {
            channel.queueDeclarePassive(queue);
        } catch (IOException e) {
            throw new IOException("A fila '" + queue + "' não pôde ser confirmada no broker (a aplicação deve "
                    + "declará-la antes de iniciar o consumidor): " + e.getMessage(), e);
        }
        channel.close();
    }

    private void closeQuietly() {
        discardConfirmChannel();
        if (consumeChannel != null) {
            try {
                if (consumeChannel.isOpen()) {
                    consumeChannel.close();
                }
            } catch (IOException | TimeoutException | ShutdownSignalException e) {
                // o canal já está fechado ou inutilizável
            }
            consumeChannel = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException | ShutdownSignalException e) {
                // a conexão já está fechada ou inutilizável
            }
            connection = null;
        }
    }

    /** Entrega do RabbitMQ: headers textuais convertidos para {@code String} e os originais guardados para a DLQ. */
    private static final class RabbitDelivery implements Delivery {

        private final long deliveryTag;
        private final int epoch;
        private final AMQP.BasicProperties properties;
        private final Map<String, Object> rawHeaders;
        private final Map<String, Object> headers;
        private final byte[] body;

        RabbitDelivery(com.rabbitmq.client.Delivery message, int epoch) {
            this.deliveryTag = message.getEnvelope().getDeliveryTag();
            this.epoch = epoch;
            this.properties = message.getProperties();
            Map<String, Object> received = properties.getHeaders();
            this.rawHeaders = received == null ? Collections.<String, Object>emptyMap() : received;
            this.headers = convert(rawHeaders);
            this.body = message.getBody();
        }

        @Override
        public Map<String, Object> headers() {
            return headers;
        }

        @Override
        public byte[] body() {
            return body;
        }

        private static Map<String, Object> convert(Map<String, Object> raw) {
            Map<String, Object> converted = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                Object value = entry.getValue();
                converted.put(entry.getKey(), value instanceof LongString ? value.toString() : value);
            }
            return Collections.unmodifiableMap(converted);
        }
    }
}
