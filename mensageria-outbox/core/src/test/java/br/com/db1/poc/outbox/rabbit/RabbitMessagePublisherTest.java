package br.com.db1.poc.outbox.rabbit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ReturnListener;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class RabbitMessagePublisherTest {

    private static final class Published {
        private final String exchange;
        private final String routingKey;
        private final boolean mandatory;
        private final AMQP.BasicProperties properties;
        private final byte[] body;

        Published(String exchange, String routingKey, boolean mandatory, AMQP.BasicProperties properties,
                byte[] body) {
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.mandatory = mandatory;
            this.properties = properties;
            this.body = body;
        }

        String exchange() {
            return exchange;
        }

        String routingKey() {
            return routingKey;
        }

        boolean mandatory() {
            return mandatory;
        }

        AMQP.BasicProperties properties() {
            return properties;
        }

        byte[] body() {
            return body;
        }
    }

    /** Canal simulado: registra as publicações e responde ao confirm conforme configurado. */
    private static final class FakeChannel implements InvocationHandler {
        final List<Published> published = new ArrayList<>();
        ReturnListener returnListener;
        boolean confirm = true;
        boolean confirmTimesOut;
        boolean returnMessages;
        boolean open = true;

        Channel channel() {
            return (Channel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Channel.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "addReturnListener":
                    returnListener = (ReturnListener) args[0];
                    return null;
                case "basicPublish":
                    Published call = new Published((String) args[0], (String) args[1], (Boolean) args[2],
                            (AMQP.BasicProperties) args[3], (byte[]) args[4]);
                    published.add(call);
                    if (returnMessages) {
                        returnListener.handleReturn(312, "NO_ROUTE", call.exchange(), call.routingKey(),
                                call.properties(), call.body());
                    }
                    return null;
                case "waitForConfirms":
                    if (confirmTimesOut) {
                        throw new TimeoutException();
                    }
                    return confirm;
                case "isOpen":
                    return open;
                case "close":
                    open = false;
                    return null;
                default:
                    return null;
            }
        }
    }

    private static Connection connectionOf(FakeChannel channel) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createChannel":
                    return channel.channel();
                case "isOpen":
                    return true;
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(RabbitMessagePublisherTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    private static PublishableMessage message() {
        return new PublishableMessage(UUID.randomUUID(), UUID.randomUUID(), "idem-1", "agg-1", "PagamentoRecebido",
                (short) 3, Instant.parse("2026-10-14T16:20:31.123456Z"), new byte[] {0, (byte) 0xFF, (byte) 0xFE},
                "trace-1");
    }

    private static RabbitMessagePublisher publisherOn(FakeChannel channel) {
        Connection connection = connectionOf(channel);
        return new RabbitMessagePublisher(() -> connection, "mensageria.outbox", Duration.ofSeconds(1));
    }

    /** TC-16: exchange, routing key, mandatory, headers e properties conforme o contrato da mensagem publicada. */
    @Test
    void mapeiaOEnvelopeParaHeadersEProperties() throws PublishException {
        FakeChannel channel = new FakeChannel();
        PublishableMessage message = message();

        publisherOn(channel).publish(message);

        assertEquals(1, channel.published.size());
        Published call = channel.published.get(0);
        assertEquals("mensageria.outbox", call.exchange());
        assertEquals("PagamentoRecebido", call.routingKey());
        assertTrue(call.mandatory());
        assertArrayEquals(message.payload(), call.body());

        Map<String, Object> headers = call.properties().getHeaders();
        assertEquals(message.messageId().toString(), headers.get("messageId"));
        assertEquals(message.correlationId().toString(), headers.get("correlationId"));
        assertEquals("idem-1", headers.get("idempotencyKey"));
        assertEquals("agg-1", headers.get("aggregateKey"));
        assertEquals("PagamentoRecebido", headers.get("type"));
        assertEquals(3, headers.get("schemaVersion"));
        assertEquals("2026-10-14T16:20:31.123456Z", headers.get("occurredAt"));
        assertEquals("trace-1", headers.get("traceId"));
        assertEquals(8, headers.size());

        AMQP.BasicProperties properties = call.properties();
        assertEquals(message.messageId().toString(), properties.getMessageId());
        assertEquals(message.correlationId().toString(), properties.getCorrelationId());
        assertEquals("PagamentoRecebido", properties.getType());
        assertEquals(2, properties.getDeliveryMode());
        assertEquals("application/octet-stream", properties.getContentType());
    }

    @Test
    void occurredAtMantemOsMicrossegundosComZeros() throws PublishException {
        FakeChannel channel = new FakeChannel();
        PublishableMessage base = message();
        PublishableMessage message = new PublishableMessage(base.messageId(), base.correlationId(),
                base.idempotencyKey(), base.aggregateKey(), base.type(), base.schemaVersion(),
                Instant.parse("2026-10-14T16:20:31Z"), base.payload(), base.traceId());

        publisherOn(channel).publish(message);

        assertEquals("2026-10-14T16:20:31.000000Z",
                channel.published.get(0).properties().getHeaders().get("occurredAt"));
    }

    @Test
    void confirmNegativoELevantaFalhaDeMensagem() {
        FakeChannel channel = new FakeChannel();
        channel.confirm = false;

        PublishException failure = assertThrows(PublishException.class, () -> publisherOn(channel).publish(message()));

        assertEquals(PublishException.Kind.MESSAGE, failure.kind());
    }

    @Test
    void mensagemDevolvidaELevantaFalhaDeMensagem() {
        FakeChannel channel = new FakeChannel();
        channel.returnMessages = true;

        PublishException failure = assertThrows(PublishException.class, () -> publisherOn(channel).publish(message()));

        assertEquals(PublishException.Kind.MESSAGE, failure.kind());
        assertTrue(failure.getMessage().contains("NO_ROUTE"));
    }

    @Test
    void timeoutDeConfirmELevantaFalhaDeConexaoEDescartaOCanal() {
        FakeChannel channel = new FakeChannel();
        channel.confirmTimesOut = true;

        PublishException failure = assertThrows(PublishException.class, () -> publisherOn(channel).publish(message()));

        assertEquals(PublishException.Kind.CONNECTION, failure.kind());
        assertEquals(false, channel.open);
    }

    @Test
    void erroDeConexaoELevantaFalhaDeConexao() {
        RabbitMessagePublisher publisher = new RabbitMessagePublisher(() -> {
            throw new IOException("recusada");
        }, "mensageria.outbox", Duration.ofSeconds(1));

        PublishException failure = assertThrows(PublishException.class, () -> publisher.publish(message()));

        assertEquals(PublishException.Kind.CONNECTION, failure.kind());
        assertNotNull(failure.getCause());
    }
}
