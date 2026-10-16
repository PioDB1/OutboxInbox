package br.com.db1.poc.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;

import com.rabbitmq.client.ConnectionFactory;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.rabbit.RabbitMessagePublisher;
import br.com.db1.poc.outbox.write.OutboxWriter;

/**
 * Sobe a auto-configuração sem banco nem broker: o publicador é fornecido pela configuração do teste, o que dispensa
 * a declaração do exchange, e o {@code DataSource} não abre conexão enquanto nada é gravado.
 */
class OutboxAutoConfigurationTest {

	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
			.withUserConfiguration(InfraestruturaDoTeste.class);

	@Test
	void registraOsBeansDaIntegracao() {
		contexto.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SqlServerOutboxDialect.class);
			assertThat(context).hasSingleBean(OutboxWriter.class);
			assertThat(context).hasSingleBean(OutboxGravador.class);
			assertThat(context).hasSingleBean(OutboxDespachante.class);
			assertThat(context).hasSingleBean(OutboxRecuperacao.class);
		});
	}

	@Test
	void aplicaOsPadroesDeOutboxRelay() {
		contexto.run(context -> {
			OutboxRelayProperties props = context.getBean(OutboxRelayProperties.class);
			assertThat(props.exchange()).isEqualTo("mensageria.outbox");
			assertThat(props.confirmTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(props.backoff().initial()).isEqualTo(Duration.ofMillis(200));
			assertThat(props.backoff().multiplier()).isEqualTo(2.0);
			assertThat(props.backoff().max()).isEqualTo(Duration.ofSeconds(30));
			assertThat(props.batchSize()).isEqualTo(100);
		});
	}

	@Test
	void leAsChavesDeOutboxRelay() {
		contexto.withPropertyValues("outbox.relay.exchange=outro.exchange", "outbox.relay.batch-size=7",
				"outbox.relay.backoff.max=10s").run(context -> {
					OutboxRelayProperties props = context.getBean(OutboxRelayProperties.class);
					assertThat(props.exchange()).isEqualTo("outro.exchange");
					assertThat(props.batchSize()).isEqualTo(7);
					assertThat(props.backoff().max()).isEqualTo(Duration.ofSeconds(10));
				});
	}

	@Test
	void preencheAFabricaDoRabbitMqPelasChavesSpringRabbitmq() {
		contexto.withPropertyValues("spring.rabbitmq.host=rabbit.local", "spring.rabbitmq.port=5679",
				"spring.rabbitmq.username=usuario", "spring.rabbitmq.password=senha",
				"spring.rabbitmq.virtual-host=/demo").run(context -> {
					ConnectionFactory factory = context.getBean("outboxRabbitConnectionFactory",
							ConnectionFactory.class);
					assertThat(factory.getHost()).isEqualTo("rabbit.local");
					assertThat(factory.getPort()).isEqualTo(5679);
					assertThat(factory.getUsername()).isEqualTo("usuario");
					assertThat(factory.getPassword()).isEqualTo("senha");
					assertThat(factory.getVirtualHost()).isEqualTo("/demo");
					assertThat(factory.isAutomaticRecoveryEnabled()).isTrue();
				});
	}

	@Test
	void recusaGravacaoForaDeTransacao() {
		contexto.run(context -> {
			OutboxGravador gravador = context.getBean(OutboxGravador.class);
			Envelope envelope = Envelope.novo(UUID.randomUUID(), "chave", "agregado", "Tipo", (short) 1,
					Instant.now(), "{}".getBytes(StandardCharsets.UTF_8), "trace");
			assertThatThrownBy(() -> gravador.gravar(envelope)).isInstanceOf(IllegalTransactionStateException.class);
		});
	}

	@Test
	void registraAAutoConfiguracaoNoSpringBoot() throws IOException {
		try (InputStream imports = getClass().getClassLoader().getResourceAsStream(
				"META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
			assertThat(imports).isNotNull();
			assertThat(new String(imports.readAllBytes(), StandardCharsets.UTF_8))
					.contains(OutboxAutoConfiguration.class.getName());
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class InfraestruturaDoTeste {

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource("jdbc:sqlserver://localhost:1;databaseName=inexistente");
		}

		@Bean(destroyMethod = "close")
		RabbitMessagePublisher outboxPublisher() {
			return new RabbitMessagePublisher(new ConnectionFactory(), "mensageria.outbox", Duration.ofSeconds(1));
		}

	}

}
