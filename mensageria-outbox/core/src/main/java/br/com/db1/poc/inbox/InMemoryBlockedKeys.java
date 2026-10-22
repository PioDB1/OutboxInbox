package br.com.db1.poc.inbox;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chaves bloqueadas guardadas na memória do processo, sem liberação durante a execução. O reinício do consumidor
 * libera todas as chaves, e a mensagem seguinte de uma chave com mensagem na DLQ passa a ser aplicada sem aquela
 * mensagem.
 */
public final class InMemoryBlockedKeys implements BlockedKeys {

    private final Set<String> keys = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public boolean isBlocked(String aggregateKey) {
        return keys.contains(aggregateKey);
    }

    @Override
    public void block(String aggregateKey) {
        keys.add(aggregateKey);
    }
}
