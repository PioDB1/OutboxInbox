package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InMemoryInboxDialect;
import br.com.db1.poc.inbox.support.ScriptedMessageSource;
import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.LogCapture;
import br.com.db1.poc.shared.support.TestEnvelopes;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Consumidor com a {@link ScriptedMessageSource}: ordem, paralelismo, ack depois do commit e falha de ack. */
class InboxConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static void awaitAcks(ConsumerFixture fixture, int expected) {
        Await.until(TIMEOUT, expected + " acks", () -> fixture.source.acked().size() >= expected);
    }

    /** TC-15 (parte simulada): o consumo começa com as filas e o limite de mensagens em voo da configuração. */
    @Test
    void startRepassaFilasELimiteEmVoo() {
        InboxConfig config = new InboxConfig("fila.teste", "fila.morta", 3, Duration.ofMillis(100), 2.0,
                Duration.ofMillis(300), 10, 2, Duration.ofSeconds(1));

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(config, (e, c) -> { })) {
            assertEquals("fila.teste", fixture.source.queue());
            assertEquals("fila.morta", fixture.source.deadLetterQueue());
            assertEquals(10, fixture.source.maxInFlight());
        }
    }

    /** TC-5: o ack só sai depois de efeito e inbox estarem visíveis a uma segunda conexão. */
    @Test
    void ackSoDepoisDoCommit() {
        H2Db db = H2Db.memory();
        List<String> seenAtAck = Collections.synchronizedList(new ArrayList<String>());
        InboxMessageHandler handler = (envelope, connection) -> H2Db.insertEffect(connection, envelope.messageId(), "x");

        try (ConsumerFixture fixture = ConsumerFixture.h2(db, ConsumerFixture.config(3, 2), handler)) {
            fixture.source.beforeAck(delivery -> seenAtAck.add(db.count("inbox") + "/" + db.count("efeito")));
            fixture.deliver(TestEnvelopes.of("agregado-1"));

            awaitAcks(fixture, 1);
        }

        assertEquals(Collections.singletonList("1/1"), seenAtAck);
    }

    /** TC-9 (pelo consumidor): a repetida recebe ack, não chama o tratador e conta como duplicata. */
    @Test
    void mensagemRepetidaRecebeAckSemTratador() {
        AtomicInteger handled = new AtomicInteger();
        Envelope envelope = TestEnvelopes.of("agregado-1");

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2),
                (e, c) -> handled.incrementAndGet())) {
            fixture.deliver(envelope);
            awaitAcks(fixture, 1);
            fixture.deliver(envelope);
            awaitAcks(fixture, 2);

            assertEquals(1, handled.get());
            assertEquals(1.0, fixture.count(InboxMetrics.DUPLICATES_DISCARDED));
            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertEquals(0.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
        }
    }

    /** TC-10 (pelo consumidor, H2): a corrida do mesmo {@code messageId} em chaves diferentes aplica uma vez. */
    @Test
    void corridaDoMesmoMessageIdAplicaUmaVez() {
        H2Db db = H2Db.memory();
        AtomicInteger handled = new AtomicInteger();
        InboxMessageHandler handler = (envelope, connection) -> {
            handled.incrementAndGet();
            H2Db.insertEffect(connection, envelope.messageId(), "x");
            Thread.sleep(200);
        };
        Envelope first = TestEnvelopes.of("chave-1");
        SimulatedDelivery a = SimulatedDelivery.of(first);
        SimulatedDelivery b = SimulatedDelivery.of(TestEnvelopes.of("chave-2"))
                .with("messageId", first.messageId().toString());

        try (ConsumerFixture fixture = ConsumerFixture.h2(db, ConsumerFixture.config(3, 4), handler)) {
            fixture.source.deliver(a);
            fixture.source.deliver(b);

            awaitAcks(fixture, 2);

            assertEquals(1, handled.get());
            assertEquals(1, db.count("inbox"));
            assertEquals(1, db.count("efeito"));
            assertEquals(1.0, fixture.count(InboxMetrics.DUPLICATES_DISCARDED));
            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertEquals(0, fixture.source.deadLettered().size());
        }
    }

    /** TC-8: ack que falha depois do commit é só um aviso; a reentrega é descartada pela inbox. */
    @Test
    void falhaDeAckNaoEFalhaDeProcessamento() {
        AtomicInteger handled = new AtomicInteger();
        Envelope envelope = TestEnvelopes.of("agregado-1");

        try (LogCapture logs = new LogCapture(InboxConsumer.class);
                ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2),
                        (e, c) -> handled.incrementAndGet())) {
            fixture.source.failNextAcks(1);
            fixture.deliver(envelope);
            Await.until(TIMEOUT, "ack falho registrado", () -> fixture.source.events().contains("ack-failed"));
            Await.until(TIMEOUT, "chave liberada", () -> fixture.consumer.activeKeys() == 0);

            assertEquals(0.0, fixture.count(InboxMetrics.PROCESS_RETRIES));
            assertEquals(0.0, fixture.count(InboxMetrics.DLQ_MESSAGES));
            assertEquals(0, fixture.source.deadLettered().size());
            List<ILoggingEvent> warnings = new ArrayList<>();
            for (ILoggingEvent event : logs.events()) {
                if (event.getLevel() == Level.WARN) {
                    warnings.add(event);
                }
            }
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).getFormattedMessage().startsWith("Ack não enviado"));

            fixture.deliver(envelope);
            awaitAcks(fixture, 1);

            assertEquals(1, handled.get());
            assertEquals(1.0, fixture.count(InboxMetrics.DUPLICATES_DISCARDED));
        }
    }

    /** TC-13: 100 mensagens da mesma chave, uma por vez, na ordem de entrega e cada uma só depois do ack da anterior. */
    @Test
    void mesmaChaveEmOrdemEUmaPorVez() {
        int total = 100;
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Random random = new Random(7);
        InboxMessageHandler handler = (envelope, connection) -> {
            String number = envelope.idempotencyKey();
            events.add("inicio:" + number);
            Thread.sleep(random.nextInt(3));
            events.add("fim:" + number);
        };

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 8), handler)) {
            fixture.source.beforeAck(delivery -> events.add("ack:" + delivery.headers().get("idempotencyKey")));
            for (int i = 0; i < total; i++) {
                Envelope base = TestEnvelopes.of("A");
                fixture.source.deliver(SimulatedDelivery.of(base).with("idempotencyKey", String.valueOf(i)));
            }

            awaitAcks(fixture, total);
        }

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            expected.add("inicio:" + i);
            expected.add("fim:" + i);
            expected.add("ack:" + i);
        }
        assertEquals(expected, new ArrayList<>(events));
    }

    /** TC-14: chaves diferentes executam o tratador ao mesmo tempo. */
    @Test
    void chavesDiferentesEmParalelo() {
        CountDownLatch allRunning = new CountDownLatch(8);
        AtomicInteger reachedAll = new AtomicInteger();
        InboxMessageHandler handler = (envelope, connection) -> {
            allRunning.countDown();
            if (allRunning.await(5, TimeUnit.SECONDS)) {
                reachedAll.incrementAndGet();
            }
        };

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 8), handler)) {
            for (int i = 0; i < 8; i++) {
                fixture.deliver(TestEnvelopes.of("chave-" + i));
            }

            awaitAcks(fixture, 8);
        }

        assertEquals(8, reachedAll.get());
    }

    /** TC-16: depois de processar 1 000 chaves diferentes, nenhuma chave fica retida. */
    @Test
    void estruturaDasChavesELiberada() {
        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 8), (e, c) -> { })) {
            for (int i = 0; i < 1_000; i++) {
                fixture.deliver(TestEnvelopes.of("chave-" + i));
            }

            awaitAcks(fixture, 1_000);
            Await.until(TIMEOUT, "nenhuma chave retida", () -> fixture.consumer.activeKeys() == 0);
        }
    }

    /** Uma chave com tratador lento não atrasa as outras. */
    @Test
    void chaveLentaNaoBloqueiaOutrasChaves() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        InboxMessageHandler handler = (envelope, connection) -> {
            if (envelope.aggregateKey().equals("lenta")) {
                release.await(10, TimeUnit.SECONDS);
            }
        };

        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2), handler)) {
            fixture.deliver(TestEnvelopes.of("lenta"));
            fixture.deliver(TestEnvelopes.of("rapida"));

            awaitAcks(fixture, 1);
            assertEquals("rapida", fixture.source.acked().get(0).headers().get("aggregateKey"));
            release.countDown();
            awaitAcks(fixture, 2);
        }
    }

    @Test
    void closeFechaAPortaEDevolveAsMensagensRecebidasDepois() {
        ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2), (e, c) -> { });
        fixture.consumer.close();

        fixture.deliver(TestEnvelopes.of("a"));

        assertTrue(fixture.source.closed());
        assertEquals(1, fixture.source.requeued().size());
        assertEquals(0, fixture.source.acked().size());
        fixture.consumer.close();
    }

    @Test
    void startDuasVezesELancaEstadoIlegal() {
        try (ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2), (e, c) -> { })) {
            assertThrows(IllegalStateException.class, fixture.consumer::start);
        }
    }

    @Test
    void falhaDoStartNomeiaAFila() {
        MessageSource failing = new MessageSource() {
            @Override
            public void start(String queue, String deadLetterQueue, int maxInFlight,
                    Consumer<Delivery> onDelivery) throws IOException {
                throw new IOException("fila ausente");
            }

            @Override
            public void ack(Delivery delivery) {
            }

            @Override
            public void requeue(Delivery delivery) {
            }

            @Override
            public void deadLetter(Delivery delivery, String failure, int attempts) {
            }

            @Override
            public void close() {
            }
        };
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        InboxConsumer consumer = new InboxConsumer(ConsumerFixture.config(3, 2), failing, dialect,
                dialect.transactions(), (e, c) -> { }, new InMemoryBlockedKeys(),
                new InboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        IllegalStateException failure = assertThrows(IllegalStateException.class, consumer::start);
        consumer.close();

        assertTrue(failure.getMessage().contains("fila.teste"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fila ausente"), failure.getMessage());
        assertFalse(failure.getMessage().isEmpty());
    }

    @Test
    void depoisDoCloseNaoReiniciaEstadoIlegal() {
        ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 2), (e, c) -> { });
        fixture.consumer.close();

        assertThrows(IllegalStateException.class, fixture.consumer::start);
    }
}
