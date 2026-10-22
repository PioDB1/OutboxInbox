package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class InboxConfigTest {

    private static Properties valid() {
        Properties properties = new Properties();
        properties.setProperty("inbox.consumer.queue", "pagamentos");
        return properties;
    }

    private static InboxConfig with(String key, String value) {
        Properties properties = valid();
        properties.setProperty("inbox.consumer." + key, value);
        return InboxConfig.fromProperties(properties);
    }

    private static void assertRejected(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> with(key, value),
                key + "=" + value);
        assertTrue(e.getMessage().contains("inbox.consumer." + key), e.getMessage());
    }

    /** TC-23: com os padrões, só a fila é obrigatória. */
    @Test
    void padroesDoComponente() {
        InboxConfig config = InboxConfig.fromProperties(valid());

        assertEquals("pagamentos", config.queue());
        assertEquals("pagamentos.dlq", config.deadLetterQueue());
        assertEquals(5, config.maxAttempts());
        assertEquals(Duration.ofMillis(200), config.backoffInitial());
        assertEquals(2.0, config.backoffMultiplier());
        assertEquals(Duration.ofSeconds(30), config.backoffMax());
        assertEquals(50, config.maxInFlight());
        assertEquals(8, config.workers());
        assertEquals(Duration.ofSeconds(5), config.confirmTimeout());
    }

    @Test
    void leTodasAsChaves() {
        Properties properties = valid();
        properties.setProperty("inbox.consumer.dead-letter-queue", "morta");
        properties.setProperty("inbox.consumer.max-attempts", "3");
        properties.setProperty("inbox.consumer.backoff.initial", "20ms");
        properties.setProperty("inbox.consumer.backoff.multiplier", "1.5");
        properties.setProperty("inbox.consumer.backoff.max", "1s");
        properties.setProperty("inbox.consumer.max-in-flight", "7");
        properties.setProperty("inbox.consumer.workers", "2");
        properties.setProperty("inbox.consumer.confirm-timeout", "PT2S");

        InboxConfig config = InboxConfig.fromProperties(properties);

        assertEquals("morta", config.deadLetterQueue());
        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofMillis(20), config.backoffInitial());
        assertEquals(1.5, config.backoffMultiplier());
        assertEquals(Duration.ofSeconds(1), config.backoffMax());
        assertEquals(7, config.maxInFlight());
        assertEquals(2, config.workers());
        assertEquals(Duration.ofSeconds(2), config.confirmTimeout());
    }

    @Test
    void filaAusenteOuEmBrancoERecusada() {
        IllegalArgumentException ausente =
                assertThrows(IllegalArgumentException.class, () -> InboxConfig.fromProperties(new Properties()));
        assertTrue(ausente.getMessage().contains("inbox.consumer.queue"));
        assertRejected("queue", "   ");
    }

    @Test
    void valoresInvalidosSaoRecusadosComAChave() {
        assertRejected("max-attempts", "0");
        assertRejected("max-attempts", "abc");
        assertRejected("backoff.initial", "0ms");
        assertRejected("backoff.multiplier", "NaN");
        assertRejected("backoff.multiplier", "Infinity");
        assertRejected("backoff.multiplier", "-Infinity");
        assertRejected("backoff.multiplier", "0.5");
        assertRejected("max-in-flight", "0");
        assertRejected("workers", "0");
        assertRejected("confirm-timeout", "0s");
        assertRejected("dead-letter-queue", "pagamentos");
    }

    @Test
    void tetoDoBackoffMenorQueOInicialERecusado() {
        Properties properties = valid();
        properties.setProperty("inbox.consumer.backoff.initial", "1s");
        properties.setProperty("inbox.consumer.backoff.max", "500ms");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> InboxConfig.fromProperties(properties));

        assertTrue(e.getMessage().contains("inbox.consumer.backoff.max"), e.getMessage());
    }

    @Test
    void construtorRecusaFilaEmBrancoEDlqIgualAFila() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new InboxConfig(" ", "dlq", 5, d, 2.0, d, 50, 8, d));
        assertThrows(IllegalArgumentException.class, () -> new InboxConfig("q", " ", 5, d, 2.0, d, 50, 8, d));
        assertThrows(IllegalArgumentException.class, () -> new InboxConfig("q", "q", 5, d, 2.0, d, 50, 8, d));
        new InboxConfig("q", "dlq", 5, d, 2.0, d, 50, 8, d);
    }
}
