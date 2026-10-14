package br.com.db1.poc.outbox.publish;

/**
 * Falha de publicação: confirm negativo, timeout de confirm, mensagem devolvida ou erro de conexão.
 *
 * <p>{@link Kind#MESSAGE} afeta somente a mensagem tentada; {@link Kind#CONNECTION} indica que o broker está
 * inacessível ou sem resposta, e o relay encerra o lote naquele ponto em vez de tentar as demais mensagens.
 */
public class PublishException extends Exception {

    /** Alcance da falha. */
    public enum Kind {
        MESSAGE,
        CONNECTION
    }

    private final Kind kind;

    /** Falha restrita à mensagem tentada. */
    public PublishException(String message) {
        this(message, null, Kind.MESSAGE);
    }

    /** Falha de conexão com o broker. */
    public PublishException(String message, Throwable cause) {
        this(message, cause, Kind.CONNECTION);
    }

    public PublishException(String message, Throwable cause, Kind kind) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
