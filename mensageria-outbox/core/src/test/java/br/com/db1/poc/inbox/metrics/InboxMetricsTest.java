package br.com.db1.poc.inbox.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class InboxMetricsTest {

    /** TC-30: os três contadores ficam registrados desde a criação, com os nomes públicos. */
    @Test
    void registraOsTresContadoresNaCriacao() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new InboxMetrics(registry);

        assertEquals("inbox.duplicates.discarded", InboxMetrics.DUPLICATES_DISCARDED);
        assertEquals("inbox.dlq.messages", InboxMetrics.DLQ_MESSAGES);
        assertEquals("inbox.process.retries", InboxMetrics.PROCESS_RETRIES);
        assertNotNull(registry.find(InboxMetrics.DUPLICATES_DISCARDED).counter());
        assertNotNull(registry.find(InboxMetrics.DLQ_MESSAGES).counter());
        assertNotNull(registry.find(InboxMetrics.PROCESS_RETRIES).counter());
        assertEquals(3, registry.getMeters().size());
    }

    @Test
    void cadaRegistroIncrementaSeuContador() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxMetrics metrics = new InboxMetrics(registry);

        metrics.recordDuplicate();
        metrics.recordRetry();
        metrics.recordRetry();
        metrics.recordDeadLetter();
        metrics.recordDeadLetter();
        metrics.recordDeadLetter();

        assertEquals(1.0, registry.counter(InboxMetrics.DUPLICATES_DISCARDED).count());
        assertEquals(2.0, registry.counter(InboxMetrics.PROCESS_RETRIES).count());
        assertEquals(3.0, registry.counter(InboxMetrics.DLQ_MESSAGES).count());
    }

    @Test
    void doisObjetosNoMesmoRegistroCompartilhamOsContadores() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboxMetrics first = new InboxMetrics(registry);
        InboxMetrics second = new InboxMetrics(registry);

        first.recordDuplicate();
        second.recordDuplicate();

        assertEquals(2.0, registry.counter(InboxMetrics.DUPLICATES_DISCARDED).count());
    }
}
