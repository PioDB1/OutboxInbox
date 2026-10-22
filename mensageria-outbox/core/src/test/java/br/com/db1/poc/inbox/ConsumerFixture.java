package br.com.db1.poc.inbox;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InMemoryInboxDialect;
import br.com.db1.poc.inbox.support.ScriptedMessageSource;
import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;

/**
 * Consumidor iniciado sobre a {@link ScriptedMessageSource}, o {@link ManualScheduler} e o registro de métricas
 * do teste, com o backoff de referência dos casos de tempo: 100 ms, fator 2,0 e teto de 300 ms.
 */
final class ConsumerFixture implements AutoCloseable {

    final ScriptedMessageSource source = new ScriptedMessageSource();
    final ManualScheduler scheduler = new ManualScheduler();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final InMemoryBlockedKeys blockedKeys = new InMemoryBlockedKeys();
    final InboxConsumer consumer;

    private ConsumerFixture(InboxConfig config, InboxDialect dialect, InboxTransactionExecutor transactions,
            InboxMessageHandler handler, InMemoryBlockedKeys sharedKeys) {
        this.consumer = new InboxConsumer(config, source, dialect, transactions, handler,
                sharedKeys == null ? blockedKeys : sharedKeys, new InboxMetrics(registry), scheduler);
        consumer.start();
    }

    static InboxConfig config(int maxAttempts, int workers) {
        return new InboxConfig("fila.teste", "fila.teste.dlq", maxAttempts, Duration.ofMillis(100), 2.0,
                Duration.ofMillis(300), 50, workers, Duration.ofSeconds(1));
    }

    /** Inbox em memória, sem banco. */
    static ConsumerFixture inMemory(InboxConfig config, InboxMessageHandler handler) {
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        return new ConsumerFixture(config, dialect, dialect.transactions(), handler, null);
    }

    /** Inbox em memória que o teste inspeciona. */
    static ConsumerFixture inMemory(InboxConfig config, InMemoryInboxDialect dialect, InboxMessageHandler handler) {
        return new ConsumerFixture(config, dialect, dialect.transactions(), handler, null);
    }

    /** Inbox e efeito em H2. */
    static ConsumerFixture h2(H2Db db, InboxConfig config, InboxMessageHandler handler) {
        return new ConsumerFixture(config, new H2InboxDialect(), new JdbcInboxTransactionExecutor(db.dataSource()),
                handler, null);
    }

    void deliver(Envelope envelope) {
        source.deliver(SimulatedDelivery.of(envelope));
    }

    double count(String meter) {
        return registry.counter(meter).count();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
