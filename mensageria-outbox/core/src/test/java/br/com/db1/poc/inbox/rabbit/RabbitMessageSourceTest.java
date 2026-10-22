package br.com.db1.poc.inbox.rabbit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.inbox.StaleDeliveryException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.LongStringHelper;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Adaptador RabbitMQ sobre canal e conexão simulados: conversão dos headers, argumentos da DLQ e entregas perdidas. */
class RabbitMessageSourceTest {

    /** Corretor simulado: registra as chamadas dos canais e permite injetar falhas. */
    private static final class FakeBroker {

        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final List<Object[]> publishes = Collections.synchronizedList(new ArrayList<Object[]>());
        final Set<String> missingQueues = new HashSet<>();
        final Set<Integer> closedChannels = new HashSet<>();
        final AtomicReference<DeliverCallback> deliverCallback = new AtomicReference<>();
        final AtomicReference<ShutdownListener> consumeShutdownListener = new AtomicReference<>();
        volatile ReturnListener returnListener;
        volatile boolean confirm = true;
        volatile boolean confirmTimesOut;
        volatile boolean returnPublished;
        volatile boolean connectionClosed;
        volatile boolean ackThrowsClosed;
        private int channelCount;

        ConnectionFactory factory() {
            Connection connection = proxy(Connection.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createChannel":
                        return channel(++channelCount);
                    case "close":
                        connectionClosed = true;
                        calls.add("connection.close");
                        return null;
                    case "isOpen":
                        return !connectionClosed;
                    default:
                        return defaultValue(method);
                }
            });
            return new ConnectionFactory() {
                @Override
                public Connection newConnection() {
                    return connection;
                }
            };
        }

        private Channel channel(int id) {
            return proxy(Channel.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "queueDeclarePassive":
                        calls.add("ch" + id + ":passive:" + args[0]);
                        if (missingQueues.contains((String) args[0])) {
                            throw new IOException("NOT_FOUND - no queue '" + args[0] + "'");
                        }
                        return null;
                    case "basicQos":
                        calls.add("ch" + id + ":qos:" + args[0]);
                        return null;
                    case "basicConsume":
                        calls.add("ch" + id + ":consume:" + args[0] + ":autoAck=" + args[1]);
                        for (Object arg : args) {
                            if (arg instanceof DeliverCallback) {
                                deliverCallback.set((DeliverCallback) arg);
                            }
                        }
                        return "tag-1";
                    case "confirmSelect":
                        calls.add("ch" + id + ":confirmSelect");
                        return null;
                    case "addReturnListener":
                        returnListener = (ReturnListener) args[0];
                        return null;
                    case "addShutdownListener":
                        if (id == consumeChannelId()) {
                            consumeShutdownListener.set((ShutdownListener) args[0]);
                        }
                        return null;
                    case "basicPublish":
                        calls.add("ch" + id + ":publish:" + args[0] + ":" + args[1] + ":mandatory=" + args[2]);
                        publishes.add(args);
                        if (returnPublished) {
                            returnListener.handleReturn(312, "NO_ROUTE", (String) args[0], (String) args[1],
                                    (AMQP.BasicProperties) args[3], (byte[]) args[4]);
                        }
                        return null;
                    case "waitForConfirms":
                        calls.add("ch" + id + ":waitForConfirms");
                        if (confirmTimesOut) {
                            throw new TimeoutException("sem confirm");
                        }
                        return confirm;
                    case "basicAck":
                        calls.add("ch" + id + ":ack:" + args[0]);
                        if (ackThrowsClosed || closedChannels.contains(id)) {
                            throw new AlreadyClosedException(new ShutdownSignalException(true, false, null, null));
                        }
                        return null;
                    case "basicNack":
                        calls.add("ch" + id + ":nack:" + args[0] + ":requeue=" + args[2]);
                        return null;
                    case "isOpen":
                        return !closedChannels.contains(id);
                    case "close":
                        calls.add("ch" + id + ":close");
                        closedChannels.add(id);
                        return null;
                    default:
                        return defaultValue(method);
                }
            });
        }

        /** O canal de consumo é o terceiro aberto: dois canais descartáveis confirmam as filas antes dele. */
        int consumeChannelId() {
            return 3;
        }

        void deliver(long tag, Map<String, Object> headers, byte[] body) throws IOException {
            AMQP.BasicProperties properties =
                    new AMQP.BasicProperties.Builder().headers(headers).contentType("application/octet-stream")
                            .deliveryMode(2).build();
            deliverCallback.get().handle("tag-1",
                    new com.rabbitmq.client.Delivery(new Envelope(tag, false, "", "fila"), properties, body));
        }

        void loseConsumeChannel() {
            closedChannels.add(consumeChannelId());
            consumeShutdownListener.get().shutdownCompleted(new ShutdownSignalException(true, false, null, null));
        }
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RabbitMessageSourceTest.class.getClassLoader(), new Class<?>[] {type},
                handler);
    }

    private static Map<String, Object> publishedHeaders() {
        Map<String, Object> headers = new HashMap<>();
        for (String name : new String[] {"messageId", "correlationId", "idempotencyKey", "aggregateKey", "type",
            "occurredAt", "traceId"}) {
            headers.put(name, LongStringHelper.asLongString(name + "-valor"));
        }
        headers.put("schemaVersion", 1);
        return headers;
    }

    private static final class Started {

        final FakeBroker broker = new FakeBroker();
        final RabbitMessageSource source = new RabbitMessageSource(broker.factory(), Duration.ofSeconds(2));
        final List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<Delivery>());

        Started() throws IOException {
            source.start("fila", "fila.dlq", 7, deliveries::add);
        }

        Delivery receive(long tag) throws IOException {
            broker.deliver(tag, publishedHeaders(), "corpo".getBytes(StandardCharsets.UTF_8));
            return deliveries.get(deliveries.size() - 1);
        }
    }

    /** TC-34 (parte simulada): as duas filas são confirmadas, o {@code basicQos} é o limite e o ack é manual. */
    @Test
    void startConfirmaAsFilasAplicaQosERegistraConsumoSemAckAutomatico() throws IOException {
        Started started = new Started();

        List<String> calls = started.broker.calls;
        assertTrue(calls.contains("ch1:passive:fila"), calls.toString());
        assertTrue(calls.contains("ch2:passive:fila.dlq"), calls.toString());
        assertTrue(calls.contains("ch3:qos:7"), calls.toString());
        assertTrue(calls.contains("ch3:consume:fila:autoAck=false"), calls.toString());
    }

    @Test
    void filaAusenteFalhaNomeandoAFilaEFechaAConexao() {
        FakeBroker broker = new FakeBroker();
        broker.missingQueues.add("fila.dlq");
        RabbitMessageSource source = new RabbitMessageSource(broker.factory(), Duration.ofSeconds(2));

        IOException failure = assertThrows(IOException.class, () -> source.start("fila", "fila.dlq", 7, d -> { }));

        assertTrue(failure.getMessage().contains("fila.dlq"), failure.getMessage());
        assertTrue(broker.connectionClosed);
    }

    /** TC-4 (parte simulada): headers de texto chegam como {@code String} e {@code schemaVersion} como número. */
    @Test
    void headersDeTextoChegamComoString() throws IOException {
        Started started = new Started();

        Delivery delivery = started.receive(1);

        for (String name : new String[] {"messageId", "correlationId", "idempotencyKey", "aggregateKey", "type",
            "occurredAt", "traceId"}) {
            assertInstanceOf(String.class, delivery.headers().get(name), name);
            assertEquals(name + "-valor", delivery.headers().get(name));
        }
        assertInstanceOf(Number.class, delivery.headers().get("schemaVersion"));
        assertArrayEquals("corpo".getBytes(StandardCharsets.UTF_8), delivery.body());
    }

    @Test
    void entregaSemHeadersTemMapaVazio() throws IOException {
        Started started = new Started();

        started.broker.deliver(1, null, new byte[0]);

        assertTrue(started.deliveries.get(0).headers().isEmpty());
    }

    @Test
    void ackESaiPeloCanalDaEntrega() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(42);

        started.source.ack(delivery);

        assertTrue(started.broker.calls.contains("ch3:ack:42"), started.broker.calls.toString());
    }

    @Test
    void requeueDevolveAoBroker() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(9);

        started.source.requeue(delivery);

        assertTrue(started.broker.calls.contains("ch3:nack:9:requeue=true"), started.broker.calls.toString());
    }

    /** TC-22 (parte simulada): a DLQ recebe corpo e headers originais mais os dois headers do consumidor. */
    @Test
    void deadLetterPublicaNoExchangePadraoComConfirmEDepoisDaAckNaOriginal() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(5);

        started.source.deadLetter(delivery, "max-attempts", 3);

        List<String> calls = started.broker.calls;
        int publish = calls.indexOf("ch4:publish::fila.dlq:mandatory=true");
        int confirm = calls.indexOf("ch4:waitForConfirms");
        int ack = calls.indexOf("ch3:ack:5");
        assertTrue(calls.contains("ch4:confirmSelect"), calls.toString());
        assertTrue(publish >= 0 && confirm > publish && ack > confirm, calls.toString());
        Object[] published = started.broker.publishes.get(0);
        AMQP.BasicProperties properties = (AMQP.BasicProperties) published[3];
        assertArrayEquals("corpo".getBytes(StandardCharsets.UTF_8), (byte[]) published[4]);
        Map<String, Object> headers = properties.getHeaders();
        assertEquals("max-attempts", headers.get("x-inbox-failure"));
        assertEquals(3, headers.get("x-inbox-attempts"));
        for (String name : new String[] {"messageId", "correlationId", "idempotencyKey", "aggregateKey", "type",
            "occurredAt", "traceId"}) {
            assertInstanceOf(LongString.class, headers.get(name), name);
            assertEquals(name + "-valor", headers.get(name).toString());
        }
        assertEquals(1, headers.get("schemaVersion"));
        assertEquals(Integer.valueOf(2), properties.getDeliveryMode());
        assertEquals("application/octet-stream", properties.getContentType());
    }

    @Test
    void deadLetterDeChaveBloqueadaTemZeroTentativas() throws IOException {
        Started started = new Started();

        started.source.deadLetter(started.receive(6), "blocked-key", 0);

        Map<String, Object> headers = ((AMQP.BasicProperties) started.broker.publishes.get(0)[3]).getHeaders();
        assertEquals("blocked-key", headers.get("x-inbox-failure"));
        assertEquals(0, headers.get("x-inbox-attempts"));
    }

    @Test
    void confirmNegativoLancaIoExceptionSemAckNaOriginal() throws IOException {
        Started started = new Started();
        started.broker.confirm = false;
        Delivery delivery = started.receive(5);

        assertThrows(IOException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));

        assertFalse(started.broker.calls.contains("ch3:ack:5"), started.broker.calls.toString());
    }

    @Test
    void timeoutDoConfirmLancaIoExceptionSemAckNaOriginal() throws IOException {
        Started started = new Started();
        started.broker.confirmTimesOut = true;
        Delivery delivery = started.receive(5);

        IOException failure = assertThrows(IOException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));

        assertFalse(failure instanceof StaleDeliveryException);
        assertFalse(started.broker.calls.contains("ch3:ack:5"), started.broker.calls.toString());
    }

    @Test
    void mensagemDevolvidaPeloBrokerLancaIoExceptionSemAckNaOriginal() throws IOException {
        Started started = new Started();
        started.broker.returnPublished = true;
        Delivery delivery = started.receive(5);

        IOException failure = assertThrows(IOException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));

        assertTrue(failure.getMessage().contains("devolvida"), failure.getMessage());
        assertFalse(started.broker.calls.contains("ch3:ack:5"), started.broker.calls.toString());
    }

    @Test
    void depoisDeUmaFalhaACanalDeConfirmEReaberto() throws IOException {
        Started started = new Started();
        started.broker.confirm = false;
        Delivery delivery = started.receive(5);
        assertThrows(IOException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));
        started.broker.confirm = true;

        started.source.deadLetter(delivery, "max-attempts", 3);

        assertTrue(started.broker.calls.contains("ch3:ack:5"), started.broker.calls.toString());
    }

    /** Uma entrega recebida antes da perda do canal não vale mais: nenhuma operação sai pelo canal novo. */
    @Test
    void entregaDeCanalPerdidoELancaStaleSemTocarNoBroker() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(5);

        started.broker.loseConsumeChannel();

        assertThrows(StaleDeliveryException.class, () -> started.source.ack(delivery));
        assertThrows(StaleDeliveryException.class, () -> started.source.requeue(delivery));
        assertThrows(StaleDeliveryException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));
        assertTrue(started.broker.calls.stream().noneMatch(call -> call.contains(":ack:") || call.contains(":nack:")
                || call.contains(":publish:")), started.broker.calls.toString());
    }

    /** Depois da recuperação do canal, as entregas novas valem e só as anteriores à queda são perdidas. */
    @Test
    void entregaRecebidaDepoisDaPerdaDoCanalVale() throws IOException {
        Started started = new Started();
        Delivery before = started.receive(5);
        started.broker.loseConsumeChannel();
        started.broker.closedChannels.remove(started.broker.consumeChannelId());

        Delivery after = started.receive(1);
        started.source.ack(after);

        assertTrue(started.broker.calls.contains("ch3:ack:1"), started.broker.calls.toString());
        assertThrows(StaleDeliveryException.class, () -> started.source.ack(before));
    }

    @Test
    void ackComCanalFechadoNoMomentoELancaStale() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(5);
        started.broker.ackThrowsClosed = true;

        assertThrows(StaleDeliveryException.class, () -> started.source.ack(delivery));
    }

    @Test
    void canalPerdidoEntreAPublicacaoEOAckELancaStale() throws IOException {
        Started started = new Started();
        Delivery delivery = started.receive(5);
        // o ack da original falha com AlreadyClosedException depois do confirm positivo
        started.broker.ackThrowsClosed = true;

        assertThrows(StaleDeliveryException.class, () -> started.source.deadLetter(delivery, "max-attempts", 3));
        assertTrue(started.broker.calls.contains("ch4:waitForConfirms"), started.broker.calls.toString());
    }

    @Test
    void closeFechaCanaisEConexaoEImpedeNovoStart() throws IOException {
        Started started = new Started();

        started.source.close();

        assertTrue(started.broker.connectionClosed);
        assertThrows(IOException.class, () -> started.source.start("fila", "fila.dlq", 7, d -> { }));
    }
}
