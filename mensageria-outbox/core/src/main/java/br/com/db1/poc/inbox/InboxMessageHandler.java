package br.com.db1.poc.inbox;

import br.com.db1.poc.shared.envelope.Envelope;
import java.sql.Connection;

/** Efeito de negócio de uma mensagem, escrito pela aplicação consumidora. */
public interface InboxMessageHandler {

    /**
     * Aplica o efeito de negócio da mensagem dentro da transação local; uma exceção desfaz a transação.
     *
     * <p>{@code connection} é a conexão da transação, a mesma em que o {@code message_id} é gravado na inbox. Com
     * JPA no Spring, é a conexão da sessão da transação Spring e o tratador pode ignorá-la. O tratador não faz
     * commit nem rollback e não fecha a conexão.
     */
    void handle(Envelope envelope, Connection connection) throws Exception;
}
