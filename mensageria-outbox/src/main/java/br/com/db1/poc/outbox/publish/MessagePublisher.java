package br.com.db1.poc.outbox.publish;

/** Porta de publicação no broker; o relay conhece o broker somente por esta interface. */
public interface MessagePublisher extends AutoCloseable {

    /**
     * Publica a mensagem e retorna somente depois do confirm positivo do broker.
     *
     * @throws PublishException confirm negativo, timeout de confirm, mensagem devolvida ou erro de conexão
     */
    void publish(PublishableMessage message) throws PublishException;

    @Override
    void close();
}
