package br.com.db1.poc.inbox;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.shared.envelope.Envelope;
import java.sql.SQLException;

/**
 * Uma tentativa de processamento de uma entrega: lê o envelope e, em uma transação local, consulta o
 * {@code messageId} na inbox, grava-o e chama o tratador. A violação da chave primária da inbox na gravação é
 * duplicata; qualquer outra exceção, inclusive uma violação de unicidade lançada pelo tratador, é falha.
 */
final class InboxProcessor {

    /** Resultado de uma tentativa que confirmou ou descartou a mensagem. */
    enum Result {
        APPLIED,
        DUPLICATE
    }

    /** Sinaliza, de dentro da transação, que o {@code messageId} já está na inbox; a transação é desfeita. */
    private static final class DuplicateMessageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DuplicateMessageException() {
            super("messageId já consta na inbox", null, false, false);
        }
    }

    private final InboxDialect dialect;
    private final InboxTransactionExecutor transactions;
    private final InboxMessageHandler handler;

    InboxProcessor(InboxDialect dialect, InboxTransactionExecutor transactions, InboxMessageHandler handler) {
        this.dialect = dialect;
        this.transactions = transactions;
        this.handler = handler;
    }

    /**
     * @return {@link Result#APPLIED} se o tratador foi chamado e a transação confirmada, {@link Result#DUPLICATE}
     *         se o {@code messageId} já constava na inbox (o tratador não é chamado e nada é gravado)
     * @throws Exception envelope inválido, conexão em autocommit, falha do banco ou do tratador; nada fica gravado
     */
    Result process(Delivery delivery) throws Exception {
        Envelope envelope = EnvelopeReader.read(delivery);
        try {
            transactions.execute(connection -> {
                if (connection.getAutoCommit()) {
                    throw new IllegalStateException(
                            "O processamento exige a transação local: a conexão está em autocommit");
                }
                if (dialect.exists(connection, envelope.messageId())) {
                    throw new DuplicateMessageException();
                }
                try {
                    dialect.insert(connection, envelope.messageId());
                } catch (SQLException e) {
                    if (dialect.isDuplicateKey(e)) {
                        throw new DuplicateMessageException();
                    }
                    throw e;
                }
                handler.handle(envelope, connection);
            });
        } catch (DuplicateMessageException e) {
            return Result.DUPLICATE;
        }
        return Result.APPLIED;
    }
}
