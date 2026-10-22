package br.com.db1.poc.inbox;

import java.io.IOException;

/**
 * A entrega não vale mais porque o canal que a entregou foi perdido: o broker devolve a mensagem à fila e a
 * entrega de novo, e a inbox descarta a reentrega se ela já foi aplicada. Repetir a operação sobre a entrega
 * antiga não tem efeito, e o consumidor a abandona.
 */
public class StaleDeliveryException extends IOException {

    private static final long serialVersionUID = 1L;

    public StaleDeliveryException(String message) {
        super(message);
    }

    public StaleDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
