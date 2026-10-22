package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.DelegatingMessageSource;
import br.com.db1.poc.inbox.support.InboxBrokerIT;
import br.com.db1.poc.inbox.support.InboxSqlServerIT;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.TestEnvelopes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A-F2: o consumidor perde a conexão com o broker depois do commit e antes do ack (RabbitMQ, Toxiproxy, SQL Server). */
class InboxCrashBetweenCommitAndAckIT extends InboxBrokerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private DataSource dataSource;

    @BeforeEach
    void prepareSqlServer() throws SQLException {
        dataSource = InboxSqlServerIT.preparedDataSource();
        InboxSqlServerIT.clean(dataSource);
    }

    /** TC-11: a reentrega depois da queda é descartada pela inbox com ack, e existe um único efeito. */
    @Test
    void reentregaDepoisDaQuedaEDescartadaPelaInbox() throws Exception {
        AtomicBoolean crashed = new AtomicBoolean();
        AtomicInteger handled = new AtomicInteger();
        List<String> receivedMessageIds = Collections.synchronizedList(new ArrayList<String>());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageSource crashing = new DelegatingMessageSource(proxiedSource()) {
            @Override
            protected void onReceived(Delivery delivery) {
                receivedMessageIds.add(String.valueOf(delivery.headers().get("messageId")));
            }

            @Override
            protected void beforeAck(Delivery delivery) {
                // commit já feito: derruba a conexão pelo Toxiproxy antes de o ack sair
                if (crashed.compareAndSet(false, true)) {
                    try {
                        broker.proxy().disable();
                        Thread.sleep(1_000);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        InboxConsumer consumer = closeAtEnd(new InboxConsumer(config(3, 50, 2), crashing,
                new SqlServerInboxDialect(), new JdbcInboxTransactionExecutor(dataSource),
                (envelope, connection) -> {
                    handled.incrementAndGet();
                    InboxSqlServerIT.insertEffect(connection, envelope.messageId(), "efeito");
                }, new InMemoryBlockedKeys(), new InboxMetrics(registry)));
        consumer.start();
        Envelope envelope = TestEnvelopes.of("agregado-1");

        publish(envelope);
        Await.until(TIMEOUT, "conexão derrubada depois do commit", crashed::get);
        broker.proxy().enable();

        Await.until(TIMEOUT, "reentrega descartada como duplicata",
                () -> registry.counter(InboxMetrics.DUPLICATES_DISCARDED).count() == 1.0);
        Await.until(TIMEOUT, "fila principal sem mensagens prontas nem sem ack", () -> {
            try {
                return readyMessages(queue) == 0 && unacknowledged(queue) == 0;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(1, handled.get());
        assertEquals(1, InboxSqlServerIT.count(dataSource, "efeito"));
        assertEquals(1, InboxSqlServerIT.count(dataSource, "inbox"));
        assertTrue(receivedMessageIds.size() >= 2, "o broker deve ter reentregue a mensagem: " + receivedMessageIds);
        assertEquals(envelope.messageId().toString(), receivedMessageIds.get(1));
        assertEquals(0, readyMessages(deadLetterQueue));
    }
}
