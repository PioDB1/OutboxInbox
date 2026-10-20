package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** TC-16: cada sufixo de duração é convertido para a unidade correspondente. */
    @Test
    void converteCadaUnidadeDeDuracao() {
        RelayConfig config = RelayConfig.fromProperties(properties(
                "strategy", "claim", "poll-interval", "750ms", "confirm-timeout", "9s", "backoff.initial", "2m",
                "backoff.max", "3h", "lease.duration", "10 s", "lease.renew-interval", "4s",
                "retention.max-age", "14d", "retention.interval", "PT90S"));

        assertEquals(Duration.ofMillis(750), config.pollInterval());
        assertEquals(Duration.ofSeconds(9), config.confirmTimeout());
        assertEquals(Duration.ofMinutes(2), config.backoffInitial());
        assertEquals(Duration.ofHours(3), config.backoffMax());
        assertEquals(Duration.ofSeconds(10), config.leaseDuration());
        assertEquals(Duration.ofDays(14), config.retentionMaxAge());
        assertEquals(Duration.ofSeconds(90), config.retentionInterval());
    }

    /** TC-17: o espaço em branco não ASCII é removido como o {@code strip()} do Java 11 faz. */
    @Test
    void removeEspacosEmBrancoNaoAsciiDoValor() {
        RelayConfig config = RelayConfig.fromProperties(properties(
                "strategy", " \u2003claim\u2003 ", "exchange", "\u2003 troca \u2003"));

        assertEquals(RelayStrategyType.CLAIM, config.strategy());
        assertEquals("troca", config.exchange());
    }

    /** TC-17: valor formado só por espaços em branco, inclusive não ASCII, é tratado como ausente. */
    @Test
    void valorSoDeEspacosEmBrancoAssumeOPadrao() {
        RelayConfig config = RelayConfig.fromProperties(properties(
                "strategy", "claim", "exchange", "\u2003\u2003", "batch-size", "   "));

        assertEquals("mensageria.outbox", config.exchange());
        assertEquals(100, config.batchSize());
    }

    @Test
    void estrategiaSoDeEspacosEmBrancoImpedeAInicializacao() {
        assertThrows(IllegalArgumentException.class,
                () -> RelayConfig.fromProperties(properties("strategy", "\u2003 \u2003")));
    }

    @Test
    void construtorRejeitaExchangeEmBrancoNaoAscii() {
        assertThrows(IllegalArgumentException.class, () -> new RelayConfig(true, RelayStrategyType.CLAIM,
                "\u2003", 100, Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofMillis(200), 2.0,
                Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofDays(7),
                Duration.ofSeconds(60), 1000));
    }

    @Test
    void comparaComponentesEImprimeNoFormatoDoRegistro() {
        RelayConfig config = RelayConfig.fromProperties(properties("strategy", "claim"));

        assertEquals(config, RelayConfig.fromProperties(properties("strategy", "claim")));
        assertEquals(config.hashCode(), RelayConfig.fromProperties(properties("strategy", "claim")).hashCode());
        assertNotEquals(config, RelayConfig.fromProperties(properties("strategy", "claim", "batch-size", "7")));
        assertNotEquals(config, RelayConfig.fromProperties(properties("strategy", "read-point")));
        assertEquals("RelayConfig[enabled=false, strategy=CLAIM, exchange=mensageria.outbox, batchSize=100, "
                + "pollInterval=PT0.5S, confirmTimeout=PT5S, backoffInitial=PT0.2S, backoffMultiplier=2.0, "
                + "backoffMax=PT30S, leaseDuration=PT15S, leaseRenewInterval=PT5S, retentionMaxAge=PT168H, "
                + "retentionInterval=PT1M, retentionBatchSize=1000]", config.toString());
    }

    @Test
    void rejeitaValoresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.fromProperties(
                properties("strategy", "claim", "backoff.initial", "10s", "backoff.max", "5s")));
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
