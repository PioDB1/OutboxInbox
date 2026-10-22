package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.InboxTransactionExecutor;
import br.com.db1.poc.inbox.dialect.InboxDialect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inbox em memória, sem banco: as inserções ficam pendentes na conexão da transação e só valem depois do commit
 * do {@link #transactions()}; o rollback as descarta. Uma inserção de um {@code messageId} confirmado ou pendente
 * em outra transação é violação de chave primária (SQLState {@code 23505}).
 */
public final class InMemoryInboxDialect implements InboxDialect {

    private final Set<UUID> committed = new HashSet<>();
    private final Map<Connection, Set<UUID>> pending = new IdentityHashMap<>();

    @Override
    public synchronized boolean exists(Connection connection, UUID messageId) {
        return committed.contains(messageId);
    }

    @Override
    public synchronized void insert(Connection connection, UUID messageId) throws SQLException {
        if (committed.contains(messageId) || isPending(messageId)) {
            throw new SQLException("chave duplicada", "23505");
        }
        pending.computeIfAbsent(connection, key -> new HashSet<>()).add(messageId);
    }

    @Override
    public boolean isDuplicateKey(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    @Override
    public String schemaScript() {
        throw new UnsupportedOperationException("sem esquema");
    }

    public synchronized int size() {
        return committed.size();
    }

    public synchronized boolean contains(UUID messageId) {
        return committed.contains(messageId);
    }

    /** Executor de transações cujo commit e rollback agem sobre esta inbox. */
    public InboxTransactionExecutor transactions() {
        return transactions(false);
    }

    /** Executor que entrega conexões com o autocommit informado. */
    public InboxTransactionExecutor transactions(boolean autoCommit) {
        return work -> {
            Connection connection = connection(autoCommit);
            try {
                work.run(connection);
            } catch (Throwable failure) {
                finish(connection, false);
                throw failure;
            }
            finish(connection, true);
        };
    }

    private boolean isPending(UUID messageId) {
        for (Set<UUID> ids : pending.values()) {
            if (ids.contains(messageId)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void finish(Connection connection, boolean commit) {
        Set<UUID> ids = pending.remove(connection);
        if (commit && ids != null) {
            committed.addAll(ids);
        }
    }

    private static Connection connection(boolean autoCommit) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
                case "getAutoCommit":
                    return autoCommit;
                case "isClosed":
                    return false;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "InMemoryConnection";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        };
        return (Connection) Proxy.newProxyInstance(
                InMemoryInboxDialect.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }
}
