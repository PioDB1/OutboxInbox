package br.com.db1.poc.inbox;

/** A mensagem não traz o envelope do contrato: header ausente, fora do formato ou acima do tamanho máximo. */
public class InvalidEnvelopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEnvelopeException(String message) {
        super(message);
    }

    public InvalidEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
