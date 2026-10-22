package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.support.H2Db;
import br.com.db1.poc.inbox.support.InMemoryInboxDialect;
import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.TestEnvelopes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Processamento de uma entrega (transação local, dedup e tratador) sobre H2 em memória, sem fila por chave. */
class InboxProcessingTest {

    private final H2Db db = H2Db.memory();
    private final AtomicInteger handled = new AtomicInteger();

    private InboxMessageHandler recordingHandler() {
        return (envelope, connection) -> {
            handled.incrementAndGet();
            H2Db.insertEffect(connection, envelope.messageId(), envelope.messageId().toString());
        };
    }

    private InboxProcessor processor(InboxMessageHandler handler) {
        return new InboxProcessor(new H2InboxDialect(), new JdbcInboxTransactionExecutor(db.dataSource()), handler);
    }

    /** TC-5 (efeito e inbox na mesma transação): mensagem nova grava a inbox e o efeito e chama o tratador. */
    @Test
    void mensagemNovaGravaEfeitoEInboxJuntos() throws Exception {
        Envelope envelope = TestEnvelopes.of("agregado-1");
        List<Envelope> received = new ArrayList<>();

        InboxProcessor.Result result = processor((e, connection) -> {
            received.add(e);
            H2Db.insertEffect(connection, e.messageId(), "x");
        }).process(SimulatedDelivery.of(envelope));

        assertEquals(InboxProcessor.Result.APPLIED, result);
        assertEquals(envelope, received.get(0));
        assertEquals(1, db.count("inbox"));
        assertEquals(1, db.count("efeito"));
    }

    @Test
    void tratadorRecebeAConexaoDaTransacaoSemAutocommit() throws Exception {
        List<Boolean> autoCommit = new ArrayList<>();

        processor((e, connection) -> autoCommit.add(connection.getAutoCommit()))
                .process(SimulatedDelivery.of(TestEnvelopes.of("a")));

        assertEquals(java.util.Collections.singletonList(false), autoCommit);
    }

    /** TC-6 (primeira tentativa): a exceção do tratador desfaz o efeito e a inbox. */
    @Test
    void falhaDoTratadorDesfazEfeitoEInbox() {
        InboxProcessor processor = processor((e, connection) -> {
            H2Db.insertEffect(connection, e.messageId(), "x");
            throw new IllegalStateException("falha do tratador");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> processor.process(SimulatedDelivery.of(TestEnvelopes.of("a"))));

        assertEquals("falha do tratador", failure.getMessage());
        assertEquals(0, db.count("inbox"));
        assertEquals(0, db.count("efeito"));
    }

    /** TC-9: o mesmo {@code messageId} de novo é descartado sem chamar o tratador. */
    @Test
    void mensagemRepetidaEDescartadaSemTratador() throws Exception {
        InboxProcessor processor = processor(recordingHandler());
        Envelope envelope = TestEnvelopes.of("a");

        InboxProcessor.Result first = processor.process(SimulatedDelivery.of(envelope));
        InboxProcessor.Result second = processor.process(SimulatedDelivery.of(envelope));

        assertEquals(InboxProcessor.Result.APPLIED, first);
        assertEquals(InboxProcessor.Result.DUPLICATE, second);
        assertEquals(1, handled.get());
        assertEquals(1, db.count("inbox"));
        assertEquals(1, db.count("efeito"));
    }

    /** TC-10 (H2): duas entregas simultâneas do mesmo {@code messageId}; só uma aplica o efeito. */
    @Test
    void duasEntregasSimultaneasDoMesmoMessageIdAplicamUmaVez() throws Exception {
        InboxProcessor processor = processor((e, connection) -> {
            handled.incrementAndGet();
            H2Db.insertEffect(connection, e.messageId(), "x");
            Thread.sleep(200);
        });
        Envelope first = TestEnvelopes.of("chave-1");
        Envelope second = TestEnvelopes.of("chave-2");
        SimulatedDelivery a = SimulatedDelivery.of(first);
        SimulatedDelivery b = SimulatedDelivery.of(second).with("messageId", first.messageId().toString());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<InboxProcessor.Result> resultA = threads.submit(() -> {
                go.await();
                return processor.process(a);
            });
            Future<InboxProcessor.Result> resultB = threads.submit(() -> {
                go.await();
                return processor.process(b);
            });
            go.countDown();

            List<InboxProcessor.Result> results = new ArrayList<>();
            results.add(resultA.get(10, TimeUnit.SECONDS));
            results.add(resultB.get(10, TimeUnit.SECONDS));

            assertTrue(results.contains(InboxProcessor.Result.APPLIED), results.toString());
            assertTrue(results.contains(InboxProcessor.Result.DUPLICATE), results.toString());
            assertEquals(1, handled.get());
            assertEquals(1, db.count("inbox"));
            assertEquals(1, db.count("efeito"));
        } finally {
            threads.shutdownNow();
        }
    }

    /** TC-12: violação de unicidade do dado de negócio é falha, não duplicata, e a tentativa seguinte aplica. */
    @Test
    void violacaoDeUnicidadeDoTratadorEFalha() throws Exception {
        db.execute("INSERT INTO efeito (message_id, valor) VALUES (RANDOM_UUID(), 'ocupado')");
        AtomicInteger attempts = new AtomicInteger();
        InboxProcessor processor = processor((e, connection) -> {
            String valor = attempts.incrementAndGet() == 1 ? "ocupado" : "livre";
            H2Db.insertEffect(connection, e.messageId(), valor);
        });
        SimulatedDelivery delivery = SimulatedDelivery.of(TestEnvelopes.of("a"));

        SQLException failure = assertThrows(SQLException.class, () -> processor.process(delivery));

        assertEquals("23505", failure.getSQLState());
        assertEquals(0, db.count("inbox"));
        assertEquals(1, db.count("efeito"));
        assertEquals(InboxProcessor.Result.APPLIED, processor.process(delivery));
        assertEquals(1, db.count("inbox"));
        assertEquals(2, db.count("efeito"));
    }

    /** TC-7: conexão em autocommit é recusada com {@link IllegalStateException}; nada é gravado. */
    @Test
    void conexaoEmAutocommitERecusada() {
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        InboxProcessor processor = new InboxProcessor(dialect, dialect.transactions(true), (e, connection) -> {
            handled.incrementAndGet();
        });

        assertThrows(IllegalStateException.class, () -> processor.process(SimulatedDelivery.of(TestEnvelopes.of("a"))));

        assertEquals(0, handled.get());
        assertEquals(0, dialect.size());
    }

    @Test
    void conexaoJdbcEmAutocommitNaoGravaNada() {
        InboxTransactionExecutor autocommit = work -> {
            try (Connection connection = db.dataSource().getConnection()) {
                work.run(connection);
            }
        };
        InboxProcessor processor = new InboxProcessor(new H2InboxDialect(), autocommit, recordingHandler());

        assertThrows(IllegalStateException.class, () -> processor.process(SimulatedDelivery.of(TestEnvelopes.of("a"))));

        assertEquals(0, handled.get());
        assertEquals(0, db.count("inbox"));
    }

    /** TC-3 (leitura): envelope inválido falha antes da transação, sem chamar o tratador. */
    @Test
    void envelopeInvalidoNaoChamaOTratador() {
        InboxProcessor processor = processor(recordingHandler());
        SimulatedDelivery delivery = SimulatedDelivery.of(TestEnvelopes.of("a")).without("messageId");

        assertThrows(InvalidEnvelopeException.class, () -> processor.process(delivery));

        assertEquals(0, handled.get());
        assertEquals(0, db.count("inbox"));
    }

    @Test
    void inboxEmMemoriaDescartaRepetidaEDesfazNaFalha() throws Exception {
        InMemoryInboxDialect dialect = new InMemoryInboxDialect();
        List<UUID> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        InboxProcessor processor = new InboxProcessor(dialect, dialect.transactions(), (e, connection) -> {
            seen.add(e.messageId());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("primeira falha");
            }
        });
        SimulatedDelivery delivery = SimulatedDelivery.of(TestEnvelopes.of("a"));

        assertThrows(IllegalStateException.class, () -> processor.process(delivery));
        assertEquals(0, dialect.size());
        assertEquals(InboxProcessor.Result.APPLIED, processor.process(delivery));
        assertEquals(InboxProcessor.Result.DUPLICATE, processor.process(delivery));
        assertEquals(1, dialect.size());
        assertEquals(2, seen.size());
        assertSame(seen.get(0), seen.get(0));
    }
}
