package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.inbox.support.SimulatedDelivery;
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.support.Await;
import br.com.db1.poc.shared.support.LogCapture;
import br.com.db1.poc.shared.support.TestEnvelopes;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class InboxMdcTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Logger HANDLER_LOG = LoggerFactory.getLogger(InboxMdcTest.class);

    private static void assertMdc(Envelope envelope, ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        assertEquals(envelope.messageId().toString(), mdc.get("messageId"), event.getFormattedMessage());
        assertEquals(envelope.correlationId().toString(), mdc.get("correlationId"), event.getFormattedMessage());
        assertEquals(envelope.aggregateKey(), mdc.get("aggregateKey"), event.getFormattedMessage());
        assertEquals(envelope.traceId(), mdc.get("traceId"), event.getFormattedMessage());
    }

    /** TC-31: todos os logs do processamento, inclusive o da repetição e o do tratador, trazem o MDC da mensagem. */
    @Test
    void mdcEmTodoOProcessamentoInclusiveNaRepeticao() {
        Envelope envelope = TestEnvelopes.of("agregado-1");
        AtomicInteger attempts = new AtomicInteger();
        InboxMessageHandler handler = (e, connection) -> {
            HANDLER_LOG.info("tratador da tentativa {}", attempts.incrementAndGet());
            if (attempts.get() == 1) {
                throw new IllegalStateException("primeira falha");
            }
        };

        try (LogCapture consumerLogs = new LogCapture(InboxConsumer.class);
                LogCapture handlerLogs = new LogCapture(InboxMdcTest.class);
                ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(3, 1), handler)) {
            fixture.deliver(envelope);
            Await.until(TIMEOUT, "repetição agendada", () -> fixture.scheduler.pending() == 1);
            fixture.scheduler.runNext();
            Await.until(TIMEOUT, "ack", () -> fixture.source.acked().size() == 1);

            List<ILoggingEvent> events = new ArrayList<>(consumerLogs.events());
            events.addAll(handlerLogs.events());
            assertEquals(2, handlerLogs.events().size());
            assertTrue(consumerLogs.events().size() >= 1, "o aviso da falha deve ter sido escrito");
            for (ILoggingEvent event : events) {
                assertMdc(envelope, event);
            }
        }
    }

    /** TC-31: a thread de trabalho devolve o MDC limpo; a mensagem seguinte, sem headers, não herda as chaves. */
    @Test
    void threadDeTrabalhoNaoGuardaOMdcDaMensagemAnterior() {
        Envelope first = TestEnvelopes.of("agregado-1");
        SimulatedDelivery withoutMdcHeaders = SimulatedDelivery.of(TestEnvelopes.of("agregado-2"))
                .without("messageId").without("correlationId").without("aggregateKey").without("traceId");

        try (LogCapture logs = new LogCapture(InboxConsumer.class);
                ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 1), (e, c) -> { })) {
            fixture.deliver(first);
            Await.until(TIMEOUT, "primeiro ack", () -> fixture.source.acked().size() == 1);

            fixture.source.deliver(withoutMdcHeaders);
            Await.until(TIMEOUT, "segunda mensagem na DLQ", () -> fixture.source.deadLettered().size() == 1);

            List<ILoggingEvent> events = logs.events();
            assertFalse(events.isEmpty());
            for (ILoggingEvent event : events) {
                if (event.getMDCPropertyMap().containsKey("messageId")) {
                    assertMdc(first, event);
                } else {
                    assertTrue(event.getMDCPropertyMap().isEmpty(), event.getMDCPropertyMap().toString());
                }
            }
            assertTrue(events.get(events.size() - 1).getMDCPropertyMap().isEmpty());
        }
    }

    /** TC-32: sem {@code messageId} e {@code correlationId}, o MDC traz só as chaves cujos headers existem. */
    @Test
    void mdcParcialParaMensagemIlegivel() {
        Envelope envelope = TestEnvelopes.of("agregado-1");
        SimulatedDelivery delivery = SimulatedDelivery.of(envelope).without("messageId").without("correlationId");

        try (LogCapture logs = new LogCapture(InboxConsumer.class);
                ConsumerFixture fixture = ConsumerFixture.inMemory(ConsumerFixture.config(1, 1), (e, c) -> { })) {
            fixture.source.deliver(delivery);
            Await.until(TIMEOUT, "mensagem na DLQ", () -> fixture.source.deadLettered().size() == 1);

            assertFalse(logs.events().isEmpty());
            for (ILoggingEvent event : logs.events()) {
                Map<String, String> mdc = event.getMDCPropertyMap();
                assertEquals(envelope.aggregateKey(), mdc.get("aggregateKey"));
                assertEquals(envelope.traceId(), mdc.get("traceId"));
                assertFalse(mdc.containsKey("messageId"), mdc.toString());
                assertFalse(mdc.containsKey("correlationId"), mdc.toString());
                for (String value : mdc.values()) {
                    assertFalse("null".equals(value));
                }
            }
        }
    }
}
