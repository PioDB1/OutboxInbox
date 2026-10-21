package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.relay.Relay;
import br.com.db1.poc.outbox.relay.RelayConfig;
import br.com.db1.poc.outbox.relay.RelayFactory;
import br.com.db1.poc.outbox.relay.RelayStrategyType;
import br.com.db1.poc.shared.support.Await;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Base dos testes de integração do relay, parametrizados pela estratégia: monta {@link RelayConfig} a partir das
 * chaves {@code outbox.relay.*} e executa ciclos até uma condição valer.
 */
public abstract class RelayIT extends BrokerIT {

    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Registro de métricas dos relays criados pelo teste. */
    protected final MeterRegistry meters = new SimpleMeterRegistry();

    /**
     * Propriedades do relay habilitado, com a estratégia dada e {@code poll-interval} curto; {@code overrides}
     * alterna chave (sem o prefixo {@code outbox.relay.}) e valor.
     */
    protected static Properties properties(RelayStrategyType strategy, String... overrides) {
        Properties properties = new Properties();
        properties.setProperty("outbox.relay.enabled", "true");
        properties.setProperty("outbox.relay.strategy", strategy.value());
        properties.setProperty("outbox.relay.exchange", EXCHANGE);
        properties.setProperty("outbox.relay.poll-interval", "50ms");
        for (int i = 0; i < overrides.length; i += 2) {
            properties.setProperty("outbox.relay." + overrides[i], overrides[i + 1]);
        }
        return properties;
    }

    /** Relay com o publicador dado, encerrado ao fim do teste. */
    protected Relay relayAs(RelayStrategyType strategy, MessagePublisher publisher, String holderId,
            String... overrides) {
        return relayAs(strategy, publisher, meters, holderId, overrides);
    }

    /** Relay que publica as métricas no registro dado, encerrado ao fim do teste. */
    protected Relay relayAs(RelayStrategyType strategy, MessagePublisher publisher, MeterRegistry registry,
            String holderId, String... overrides) {
        RelayConfig config = RelayConfig.fromProperties(properties(strategy, overrides));
        return closeAtEnd(RelayFactory.create(config, dataSource, publisher, registry, holderId));
    }

    protected Relay relay(RelayStrategyType strategy, MessagePublisher publisher, String... overrides) {
        return relayAs(strategy, publisher, "relay-test", overrides);
    }

    /** Executa ciclos do relay até a condição valer, com pausas curtas entre ciclos sem trabalho. */
    protected static void runUntil(Relay relay, String description, BooleanSupplier condition) {
        runUntil(relay, TIMEOUT, description, condition);
    }

    protected static void runUntil(Relay relay, Duration timeout, String description, BooleanSupplier condition) {
        Await.until(timeout, description, () -> {
            relay.runOnce();
            return condition.getAsBoolean();
        });
    }
}
