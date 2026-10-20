package br.com.db1.poc.outbox.rabbit;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publicador RabbitMQ com confirm por mensagem e {@code mandatory=true}.
 *
 * <p>O confirm positivo do broker também chega para uma mensagem que nenhuma fila recebeu e que o broker
 * descartou; por isso a mensagem devolvida por {@code basic.return} conta como falha de publicação. O broker
 * envia o {@code basic.return} antes do {@code basic.ack} da mesma mensagem, e o cliente processa os dois na
 * mesma thread de leitura, de modo que a devolução já está registrada quando o confirm retorna.
 *
 * <p>A conexão é aberta na primeira publicação e reaberta depois de uma falha. O contrato da mensagem está em
 * {@code contracts/mensagem-publicada.md} da spec do módulo.
 */
public final class RabbitMessagePublisher implements MessagePublisher {

    static final String CONTENT_TYPE = "application/octet-stream";
    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** Abre uma conexão com o broker. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection open() throws IOException, TimeoutException;
    }

    private final ConnectionSource connections;
    private final String exchange;
    private final long confirmTimeoutMillis;
    private final AtomicReference<String> returned = new AtomicReference<>();

    private Connection connection;
    private Channel channel;

    public RabbitMessagePublisher(ConnectionFactory factory, String exchange, Duration confirmTimeout) {
        this(factory::newConnection, exchange, confirmTimeout);
    }

    RabbitMessagePublisher(ConnectionSource connections, String exchange, Duration confirmTimeout) {
        this.connections = connections;
        this.exchange = exchange;
        this.confirmTimeoutMillis = confirmTimeout.toMillis();
    }

    @Override
    public synchronized void publish(PublishableMessage message) throws PublishException {
        Channel current;
        try {
            current = openChannel();
        } catch (IOException | TimeoutException | ShutdownSignalException e) {
            discardChannel();
            throw new PublishException("Broker inacessível: " + e.getMessage(), e);
        }
        returned.set(null);
        boolean confirmed;
        try {
            current.basicPublish(exchange, message.type(), true, properties(message), message.payload());
            confirmed = current.waitForConfirms(confirmTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discardChannel();
            throw new PublishException("Publicação interrompida", e);
        } catch (TimeoutException e) {
            discardChannel();
            throw new PublishException("Confirm não recebido em " + confirmTimeoutMillis + " ms", e);
        } catch (IOException | ShutdownSignalException e) {
            discardChannel();
            throw new PublishException("Falha ao publicar: " + e.getMessage(), e);
        }
        if (!confirmed) {
            throw new PublishException("Confirm negativo do broker para a routing key " + message.type());
        }
        String devolucao = returned.get();
        if (devolucao != null) {
            throw new PublishException(
                    "Mensagem devolvida pelo broker (" + devolucao + ") para a routing key " + message.type());
        }
    }

    @Override
    public synchronized void close() {
        discardChannel();
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException | ShutdownSignalException e) {
                // a conexão já está fechada ou inutilizável
            }
            connection = null;
        }
    }

    private Channel openChannel() throws IOException, TimeoutException {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        if (connection == null || !connection.isOpen()) {
            connection = connections.open();
        }
        Channel opened = connection.createChannel();
        opened.confirmSelect();
        opened.addReturnListener((replyCode, replyText, ex, routingKey, props, body) ->
                returned.set(replyCode + " " + replyText));
        channel = opened;
        return opened;
    }

    private void discardChannel() {
        if (channel != null) {
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException | TimeoutException | ShutdownSignalException e) {
                // o canal já está fechado ou inutilizável
            }
            channel = null;
        }
    }

    static AMQP.BasicProperties properties(PublishableMessage message) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("messageId", message.messageId().toString());
        headers.put("correlationId", message.correlationId().toString());
        headers.put("idempotencyKey", message.idempotencyKey());
        headers.put("aggregateKey", message.aggregateKey());
        headers.put("type", message.type());
        headers.put("schemaVersion", (int) message.schemaVersion());
        headers.put("occurredAt", OCCURRED_AT_FORMAT.format(message.occurredAt()));
        headers.put("traceId", message.traceId());
        return new AMQP.BasicProperties.Builder()
                .messageId(message.messageId().toString())
                .correlationId(message.correlationId().toString())
                .type(message.type())
                .deliveryMode(2)
                .contentType(CONTENT_TYPE)
                .headers(headers)
                .build();
    }
}
