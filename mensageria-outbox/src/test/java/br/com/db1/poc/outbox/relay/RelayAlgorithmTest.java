package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.support.Await;
import br.com.db1.poc.outbox.support.ScriptedPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Lógica das duas estratégias sobre uma outbox em memória: ordem por {@code aggregateKey}, linhas de transações
 * abertas, falhas, ponto de leitura e lease. Complementa os testes de integração, que exercitam o SQL Server.
 */
class RelayAlgorithmTest {

    private final InMemoryDialect dialect = new InMemoryDialect();
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private Relay relay(RelayStrategyType strategy, ScriptedPublisher publisher, String holderId, String... extra) {
        Properties properties = new Properties();
        properties.setProperty("outbox.relay.enabled", "true");
        properties.setProperty("outbox.relay.strategy", strategy.value());
        properties.setProperty("outbox.relay.backoff.initial", "20ms");
        properties.setProperty("outbox.relay.backoff.max", "40ms");
        for (int i = 0; i < extra.length; i += 2) {
            properties.setProperty("outbox.relay." + extra[i], extra[i + 1]);
        }
        RelayConfig config = RelayConfig.fromProperties(properties);
        return switch (strategy) {
            case CLAIM -> new ClaimRelay(config, dialect.dataSource(), dialect, publisher, meters, holderId);
            case READ_POINT -> new ReadPointRelay(config, dialect.dataSource(), dialect, publisher, meters, holderId);
        };
    }

    private static List<UUID> ids(ScriptedPublisher publisher) {
        return publisher.delivered().stream().map(m -> m.messageId()).toList();
    }

    private static void runUntil(Relay relay, String description, java.util.function.BooleanSupplier condition) {
        Await.until(Duration.ofSeconds(10), description, () -> {
            relay.runOnce();
            return condition.getAsBoolean();
        });
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void publicaEmOrdemELinhasDoMesmoAggregateKeyNaSequencia(RelayStrategyType strategy) {
        Envelope a1 = InMemoryDialect.envelope("A");
        Envelope b1 = InMemoryDialect.envelope("B");
        Envelope a2 = InMemoryDialect.envelope("A");
        dialect.add(a1);
        dialect.add(b1);
        dialect.add(a2);
        ScriptedPublisher publisher = new ScriptedPublisher();

        assertEquals(3, relay(strategy, publisher, "r").runOnce());

        assertEquals(List.of(a1.messageId(), b1.messageId(), a2.messageId()), ids(publisher));
        if (strategy == RelayStrategyType.CLAIM) {
            assertEquals(0, dialect.pendingCount());
        } else {
            assertEquals(3L, dialect.readPoint);
        }
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void linhaDeTransacaoAbertaRetemAPosteriorDaMesmaChave(RelayStrategyType strategy) {
        Envelope open = InMemoryDialect.envelope("X");
        Envelope committed = InMemoryDialect.envelope("X");
        InMemoryDialect.Stored openRow = dialect.addOpen(open);
        dialect.add(committed);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, "r");

        relay.runOnce();
        relay.runOnce();
        assertEquals(List.of(), ids(publisher));

        dialect.commit(openRow);
        runUntil(relay, "duas linhas publicadas", () -> publisher.delivered().size() == 2);

        assertEquals(List.of(open.messageId(), committed.messageId()), ids(publisher));
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void linhaDeTransacaoAbertaNaoImpedeOutraChaveNemELaPulada(RelayStrategyType strategy) {
        Envelope fromOpen = InMemoryDialect.envelope("T1");
        Envelope other = InMemoryDialect.envelope("T2");
        InMemoryDialect.Stored openRow = dialect.addOpen(fromOpen);
        dialect.add(other);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, "r");

        relay.runOnce();
        dialect.commit(openRow);
        runUntil(relay, "duas linhas publicadas", () -> publisher.delivered().size() == 2);

        assertTrue(ids(publisher).containsAll(List.of(fromOpen.messageId(), other.messageId())));
    }

    @Test
    void claimPublicaOutraChaveEnquantoATransacaoAbertaSegura() {
        Envelope fromOpen = InMemoryDialect.envelope("T1");
        Envelope other = InMemoryDialect.envelope("T2");
        dialect.addOpen(fromOpen);
        dialect.add(other);
        ScriptedPublisher publisher = new ScriptedPublisher();

        relay(RelayStrategyType.CLAIM, publisher, "r").runOnce();

        assertEquals(List.of(other.messageId()), ids(publisher));
    }

    @Test
    void transacaoRevertidaDeixaDeBloquearAChaveNoClaim() {
        Envelope fromOpen = InMemoryDialect.envelope("X");
        Envelope committed = InMemoryDialect.envelope("X");
        InMemoryDialect.Stored openRow = dialect.addOpen(fromOpen);
        dialect.add(committed);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(RelayStrategyType.CLAIM, publisher, "r");
        relay.runOnce();
        assertEquals(List.of(), ids(publisher));

        dialect.rollback(openRow);
        relay.runOnce();

        assertEquals(List.of(committed.messageId()), ids(publisher));
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void falhaDeUmaChaveRetemAPosteriorEAsOutrasSeguem(RelayStrategyType strategy) {
        Envelope x1 = InMemoryDialect.envelope("X");
        Envelope y1 = InMemoryDialect.envelope("Y");
        Envelope x2 = InMemoryDialect.envelope("X");
        dialect.add(x1);
        dialect.add(y1);
        dialect.add(x2);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> m.messageId().equals(x1.messageId()));
        Relay relay = relay(strategy, publisher, "r");

        assertEquals(1, relay.runOnce());
        assertEquals(List.of(y1.messageId()), ids(publisher));

        publisher.stopFailing();
        runUntil(relay, "três linhas publicadas", () -> publisher.delivered().size() == 3);

        assertEquals(List.of(y1.messageId(), x1.messageId(), x2.messageId()), ids(publisher));
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void falhaDeConexaoEncerraOLoteNaquelePonto(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        dialect.add(InMemoryDialect.envelope("B"));
        dialect.add(InMemoryDialect.envelope("C"));
        ScriptedPublisher publisher = new ScriptedPublisher().failAll().failWith(PublishException.Kind.CONNECTION);

        assertEquals(0, relay(strategy, publisher, "r").runOnce());

        assertEquals(1, publisher.attempts().size());
        assertEquals(3, dialect.pendingCount());
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void backoffImpedeNovaTentativaAntesDoFim(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        ScriptedPublisher publisher = new ScriptedPublisher().failAll();
        Relay relay = relay(strategy, publisher, "r", "backoff.initial", "5s", "backoff.max", "5s");

        relay.runOnce();
        relay.runOnce();
        relay.runOnce();

        assertEquals(1, publisher.attempts().size());
    }

    @Test
    void claimDesfazOLoteQuandoOPublicadorLancaExcecaoInesperada() {
        InMemoryDialect.Stored row = dialect.add(InMemoryDialect.envelope("A"));
        ScriptedPublisher publisher = new ScriptedPublisher().after(m -> {
            throw new IllegalStateException("queda depois do confirm");
        });

        assertThrows(IllegalStateException.class, () -> relay(RelayStrategyType.CLAIM, publisher, "r").runOnce());

        assertFalse(row.sent);
        assertEquals(0, dialect.commits);
    }

    @Test
    void pontoDeLeituraAvancaAtePrefixoContiguoEDepoisSaltaAsPublicadasAFrente() {
        Envelope r1 = InMemoryDialect.envelope("A");
        Envelope r2 = InMemoryDialect.envelope("B");
        Envelope r3 = InMemoryDialect.envelope("C");
        InMemoryDialect.Stored s1 = dialect.add(r1);
        dialect.add(r2);
        InMemoryDialect.Stored s3 = dialect.add(r3);
        ScriptedPublisher publisher = new ScriptedPublisher().failWhen(m -> m.messageId().equals(r2.messageId()));
        Relay relay = relay(RelayStrategyType.READ_POINT, publisher, "r");

        relay.runOnce();

        assertEquals(List.of(r1.messageId(), r3.messageId()), ids(publisher));
        assertEquals(s1.rv, dialect.readPoint);

        publisher.stopFailing();
        runUntil(relay, "ponto no fim", () -> dialect.readPoint == s3.rv);

        assertEquals(List.of(r1.messageId(), r3.messageId(), r2.messageId()), ids(publisher));
    }

    @Test
    void pontoDeLeituraRetomaDoBancoNumRelayNovo() {
        dialect.add(InMemoryDialect.envelope("A"));
        dialect.add(InMemoryDialect.envelope("B"));
        Relay first = relay(RelayStrategyType.READ_POINT, new ScriptedPublisher(), "r1");
        assertEquals(2, first.runOnce());
        first.close();
        Envelope third = InMemoryDialect.envelope("C");
        dialect.add(third);
        ScriptedPublisher publisher = new ScriptedPublisher();

        assertEquals(1, relay(RelayStrategyType.READ_POINT, publisher, "r2").runOnce());

        assertEquals(List.of(third.messageId()), ids(publisher));
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void semOLeaseNaoPublica(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        ScriptedPublisher holderPublisher = new ScriptedPublisher();
        ScriptedPublisher otherPublisher = new ScriptedPublisher();
        Relay holder = relay(strategy, holderPublisher, "holder");
        Relay other = relay(strategy, otherPublisher, "other");

        assertEquals(1, holder.runOnce());
        assertEquals(0, other.runOnce());

        assertEquals(0, otherPublisher.attempts().size());
        assertEquals("holder", dialect.leaseHolder);
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void relayDesabilitadoNaoAdquireLeaseNemPublica(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, "off", "enabled", "false");

        relay.start();
        assertEquals(0, relay.runOnce());

        assertEquals(null, dialect.leaseHolder);
        assertEquals(0, publisher.attempts().size());
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void closeLiberaOLeaseParaOutroRelay(RelayStrategyType strategy) {
        dialect.add(InMemoryDialect.envelope("A"));
        Relay first = relay(strategy, new ScriptedPublisher(), "first");
        first.runOnce();
        first.close();
        dialect.add(InMemoryDialect.envelope("B"));
        ScriptedPublisher publisher = new ScriptedPublisher();

        assertEquals(1, relay(strategy, publisher, "second").runOnce());
    }

    @ParameterizedTest
    @EnumSource(RelayStrategyType.class)
    void threadDoRelayPublicaEEncerraNoClose(RelayStrategyType strategy) {
        Envelope envelope = InMemoryDialect.envelope("A");
        dialect.add(envelope);
        ScriptedPublisher publisher = new ScriptedPublisher();
        Relay relay = relay(strategy, publisher, "thread", "poll-interval", "10ms");

        relay.start();
        Await.until(Duration.ofSeconds(10), "linha publicada", () -> publisher.delivered().size() == 1);
        dialect.add(InMemoryDialect.envelope("A"));
        Await.until(Duration.ofSeconds(10), "segunda linha publicada", () -> publisher.delivered().size() == 2);
        relay.close();

        assertEquals(envelope.messageId(), publisher.delivered().get(0).messageId());
        assertEquals(0L, dialect.leaseExpiresAtMillis);
    }
}
