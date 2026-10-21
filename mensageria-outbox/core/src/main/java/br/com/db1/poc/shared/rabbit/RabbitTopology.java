package br.com.db1.poc.shared.rabbit;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Topologia de destino das mensagens: exchange {@code direct} durável e fila {@code quorum} durável.
 *
 * <p>Todo declarante de uma fila repete exatamente os mesmos argumentos; uma declaração com argumentos
 * diferentes recebe {@code PRECONDITION_FAILED} do broker.
 */
public final class RabbitTopology {

    private static final Map<String, Object> QUORUM_ARGUMENTS =
            Collections.<String, Object>singletonMap("x-queue-type", "quorum");

    private RabbitTopology() {
    }

    public static void declareExchange(Channel channel, String exchange) throws IOException {
        channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, true);
    }

    public static void declareQuorumQueue(Channel channel, String queue, String exchange, String routingKey)
            throws IOException {
        channel.queueDeclare(queue, true, false, false, QUORUM_ARGUMENTS);
        channel.queueBind(queue, exchange, routingKey);
    }
}
