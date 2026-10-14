package br.com.db1.poc.outbox.relay;

/** Falha de acesso ao banco durante um ciclo do relay. */
public class RelayException extends RuntimeException {

    public RelayException(String message, Throwable cause) {
        super(message, cause);
    }
}
