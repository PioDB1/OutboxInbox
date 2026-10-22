package br.com.db1.poc.inbox;

/**
 * Chaves de agregado com mensagem na DLQ. Enquanto a chave está bloqueada, as mensagens dela vão para a DLQ sem
 * passar pelo tratador, para que nenhuma seja aplicada depois da mensagem perdida.
 */
public interface BlockedKeys {

    boolean isBlocked(String aggregateKey);

    void block(String aggregateKey);
}
