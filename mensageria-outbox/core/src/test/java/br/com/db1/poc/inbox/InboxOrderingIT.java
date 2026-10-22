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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ordem por agregado e limite em voo com RabbitMQ e SQL Server. */
class InboxOrderingIT extends InboxBrokerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private DataSource dataSource;

    @BeforeEach
    void prepareSqlServer() throws SQLException {
        dataSource = InboxSqlServerIT.preparedDataSource();
        InboxSqlServerIT.clean(dataSource);
    }

    private static Envelope numbered(String aggregateKey, int number) {
        return Envelope.novo(UUID.randomUUID(), String.valueOf(number), aggregateKey, TestEnvelopes.TYPE, (short) 1,
                Instant.now().truncatedTo(ChronoUnit.MICROS), new byte[] {1}, "trace-" + number);
    }

    private InboxConsumer startConsumer(InboxConfig config, MessageSource source, InboxMessageHandler handler) {
        InboxConsumer consumer = closeAtEnd(new InboxConsumer(config, source, new SqlServerInboxDialect(),
                new JdbcInboxTransactionExecutor(dataSource), handler, new InMemoryBlockedKeys(),
                new InboxMetrics(new SimpleMeterRegistry())));
        consumer.start();
        return consumer;
    }

    /** TC-13 (com RabbitMQ): a ordem de publicação é a de aplicação, uma por vez, cada uma depois do ack da anterior. */
    @Test
    void mesmaChaveEmOrdemDePublicacaoEUmaPorVez() throws Exception {
        int total = 100;
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Random random = new Random(11);
        MessageSource observed = new DelegatingMessageSource(directSource()) {
            @Override
            protected void beforeAck(Delivery delivery) {
                events.add("ack:" + delivery.headers().get("idempotencyKey"));
            }
        };
        startConsumer(config(3, 50, 8), observed, (envelope, connection) -> {
            events.add("inicio:" + envelope.idempotencyKey());
            InboxSqlServerIT.insertEffect(connection, envelope.messageId(), envelope.idempotencyKey());
            Thread.sleep(random.nextInt(3));
            events.add("fim:" + envelope.idempotencyKey());
        });
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            envelopes.add(numbered("A", i));
        }

        publishAll(envelopes);

        Await.until(TIMEOUT, total + " mensagens aplicadas", () -> events.contains("ack:" + (total - 1)));
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            expected.add("inicio:" + i);
            expected.add("fim:" + i);
            expected.add("ack:" + i);
        }
        assertEquals(expected, new ArrayList<>(events));
    }

    /** TC-15 (com broker): as mensagens que aguardam a chave contam no limite; nunca há mais de 10 sem ack. */
    @Test
    void mensagensAguardandoAChaveContamNoLimiteEmVoo() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        startConsumer(config(3, 10, 4), directSource(), (envelope, connection) -> {
            release.await(120, TimeUnit.SECONDS);
            InboxSqlServerIT.insertEffect(connection, envelope.messageId(), envelope.idempotencyKey());
        });
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            envelopes.add(numbered("A", i));
        }

        publishAll(envelopes);

        Await.until(TIMEOUT, "10 mensagens sem ack", () -> {
            try {
                return unacknowledged(queue) == 10;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(90, readyMessages(queue));
        assertTrue(unacknowledged(queue) <= 10);
        release.countDown();
        Await.until(TIMEOUT, "100 mensagens aplicadas", () -> {
            try {
                return InboxSqlServerIT.count(dataSource, "efeito") == 100;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
