package br.com.db1.poc.inbox;

import java.sql.Connection;

/** Executa um trabalho em uma transação local do banco da aplicação consumidora. */
public interface InboxTransactionExecutor {

    /** Executa o trabalho em uma transação: commit se ele retorna, rollback se ele lança. */
    void execute(TransactionWork work) throws Exception;

    /** Trabalho executado dentro da transação, com a conexão dela. */
    interface TransactionWork {

        void run(Connection connection) throws Exception;
    }
}
