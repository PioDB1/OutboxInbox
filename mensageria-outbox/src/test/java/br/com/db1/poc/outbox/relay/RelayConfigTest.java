package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RelayConfigTest {

    private static Properties properties(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty("outbox.relay." + keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }

    /** TC-24: estratégia ausente impede a inicialização, com os valores aceitos na mensagem. */
    @Test
    void estrategiaAusenteImpedeAInicializacao() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> RelayConfig.fromProperties(properties()));

        assertTrue(failure.getMessage().contains("claim"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-point"), failure.getMessage());
    }

    /** TC-24: estratégia desconhecida impede a inicialização, com os valores aceitos na mensagem. */
    @Test
    void estrategiaDesconhecidaImpedeAInicializacao() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RelayConfig.fromProperties(properties("strategy", "outra")));

        assertTrue(failure.getMessage().contains("outra"), failure.getMessage());
        assertTrue(failure.getMessage().contains("claim"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-point"), failure.getMessage());
    }

    /** TC-40: o relay vem desabilitado e as demais chaves assumem os valores padrão do plano. */
    @Test
    void valoresPadraoDeTodasAsChaves() {
        RelayConfig config = RelayConfig.fromProperties(properties("strategy", "claim"));

        assertFalse(config.enabled());
        assertEquals(RelayStrategyType.CLAIM, config.strategy());
        assertEquals("mensageria.outbox", config.exchange());
        assertEquals(100, config.batchSize());
        assertEquals(Duration.ofMillis(500), config.pollInterval());
        assertEquals(Duration.ofSeconds(5), config.confirmTimeout());
        assertEquals(Duration.ofMillis(200), config.backoffInitial());
        assertEquals(2.0, config.backoffMultiplier());
        assertEquals(Duration.ofSeconds(30), config.backoffMax());
        assertEquals(Duration.ofSeconds(15), config.leaseDuration());
        assertEquals(Duration.ofSeconds(5), config.leaseRenewInterval());
        assertEquals(Duration.ofDays(7), config.retentionMaxAge());
        assertEquals(Duration.ofSeconds(60), config.retentionInterval());
        assertEquals(1000, config.retentionBatchSize());
    }

    @Test
    void leEstrategiaEValoresInformados() {
        RelayConfig config = RelayConfig.fromProperties(properties(
                "strategy", "read-point", "enabled", "true", "batch-size", "10", "poll-interval", "2s",
                "backoff.max", "PT1M", "lease.duration", "3s", "lease.renew-interval", "1s",
                "retention.max-age", "2h"));

        assertTrue(config.enabled());
        assertEquals(RelayStrategyType.READ_POINT, config.strategy());
        assertEquals(10, config.batchSize());
        assertEquals(Duration.ofSeconds(2), config.pollInterval());
        assertEquals(Duration.ofMinutes(1), config.backoffMax());
        assertEquals(Duration.ofSeconds(3), config.leaseDuration());
        assertEquals(Duration.ofHours(2), config.retentionMaxAge());
    }

    @Test
    void rejeitaValoresInvalidos() {
        assertThrows(IllegalArgumentException.class,
                () -> RelayConfig.fromProperties(properties("strategy", "claim", "batch-size", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> RelayConfig.fromProperties(properties("strategy", "claim", "poll-interval", "rapido")));
        assertThrows(IllegalArgumentException.class,
                () -> RelayConfig.fromProperties(properties("strategy", "claim", "backoff.multiplier", "0.5")));
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.fromProperties(
                properties("strategy", "claim", "lease.duration", "5s", "lease.renew-interval", "5s")));
    }
}
