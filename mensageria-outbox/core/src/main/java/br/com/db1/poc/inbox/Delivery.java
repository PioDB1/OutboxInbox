package br.com.db1.poc.inbox;

import java.util.Map;

/**
 * Mensagem recebida do broker, na forma que a porta de consumo entrega ao consumidor. Nenhum tipo do broker
 * aparece aqui.
 */
public interface Delivery {

    /** Headers da mensagem; os valores textuais são {@code String} e {@code schemaVersion} é {@code Number}. */
    Map<String, Object> headers();

    /** Corpo da mensagem, entregue ao tratador sem alteração. */
    byte[] body();
}
