package br.com.db1.poc.inbox;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Porta de consumo: o consumidor conhece o broker só por esta interface; trocar de broker exige um novo adaptador.
 * O adaptador RabbitMQ é {@code br.com.db1.poc.inbox.rabbit.RabbitMessageSource}.
 */
public interface MessageSource extends AutoCloseable {

    /**
     * Confirma a existência das duas filas e começa a entregar mensagens, sem confirmação automática.
     *
     * @param maxInFlight máximo de mensagens entregues e ainda sem ack
     * @param onDelivery  recebe cada mensagem; só enfileira e nunca bloqueia
     * @throws IOException o broker está inacessível ou uma das filas não existe
     */
    void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery)
            throws IOException;

    /**
     * Confirma a mensagem ao broker; só depois do commit da transação local.
     *
     * @throws StaleDeliveryException a entrega não vale mais (o canal que a entregou foi perdido)
     */
    void ack(Delivery delivery) throws IOException;

    /** Devolve a mensagem ao broker sem processá-la. */
    void requeue(Delivery delivery) throws IOException;

    /**
     * Publica a mensagem na DLQ com confirm positivo do broker e só então dá o ack da original.
     *
     * @param failure  {@code max-attempts} ou {@code blocked-key}
     * @param attempts tentativas de processamento feitas; {@code 0} para {@code blocked-key}
     * @throws StaleDeliveryException a entrega não vale mais; a original volta ao consumidor pelo broker
     * @throws IOException            a publicação não foi confirmada; a original permanece sem ack
     */
    void deadLetter(Delivery delivery, String failure, int attempts) throws IOException;

    @Override
    void close();
}
