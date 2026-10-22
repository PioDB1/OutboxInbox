package br.com.db1.poc.outbox.spring;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.rabbit.RabbitMessagePublisher;
import br.com.db1.poc.outbox.write.OutboxWriter;
import br.com.db1.poc.shared.rabbit.RabbitTopology;

/**
 * Integra a biblioteca {@code mensageria-outbox} ao Spring Boot: gravação na transação da aplicação
 * ({@link OutboxGravador}), envio assíncrono após o commit ({@link OutboxDespachante}) e recuperação dos pendentes na
 * inicialização ({@link OutboxRecuperacao}).
 * <p>
 * As tabelas da outbox são criadas por script de esquema executado no banco antes de subir a aplicação; a
 * auto-configuração não chama o {@code OutboxSchemaInstaller}. O relay por polling da biblioteca não é instanciado:
 * ele competiria pelas mesmas linhas pendentes com outra política de envio.
 * <p>
 * A fábrica de conexões do RabbitMQ ({@code outboxRabbitConnectionFactory}) vem de
 * {@link OutboxRabbitConnectionFactoryConfiguration}, a única classe que difere entre as linhas do Spring Boot.
 */
@AutoConfiguration
@EnableConfigurationProperties(OutboxRelayProperties.class)
@Import(OutboxRabbitConnectionFactoryConfiguration.class)
public class OutboxAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	SqlServerOutboxDialect outboxDialect() {
		return new SqlServerOutboxDialect();
	}

	@Bean
	@ConditionalOnMissingBean
	OutboxWriter outboxWriter(SqlServerOutboxDialect outboxDialect) {
		return new OutboxWriter(outboxDialect);
	}

	/**
	 * Declara o exchange na inicialização e registra o publicador com confirms. As filas são declaradas pela aplicação,
	 * uma por tipo de evento, com {@link RabbitTopology#declareQuorumQueue}: a publicação usa {@code mandatory=true}, e
	 * sem fila ligada à routing key o reenvio se repete indefinidamente.
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	RabbitMessagePublisher outboxPublisher(
			@Qualifier("outboxRabbitConnectionFactory") ConnectionFactory outboxRabbitConnectionFactory,
			OutboxRelayProperties props) throws IOException, TimeoutException {
		try (Connection connection = outboxRabbitConnectionFactory.newConnection();
				Channel channel = connection.createChannel()) {
			RabbitTopology.declareExchange(channel, props.exchange());
		}
		return new RabbitMessagePublisher(outboxRabbitConnectionFactory, props.exchange(), props.confirmTimeout());
	}

	@Bean
	@ConditionalOnMissingBean
	OutboxGravador outboxGravador(OutboxWriter outboxWriter, DataSource dataSource,
			ApplicationEventPublisher eventos) {
		return new OutboxGravador(outboxWriter, dataSource, eventos);
	}

	@Bean
	@ConditionalOnMissingBean
	OutboxDespachante outboxDespachante(RabbitMessagePublisher outboxPublisher, SqlServerOutboxDialect outboxDialect,
			DataSource dataSource, OutboxRelayProperties props) {
		return new OutboxDespachante(outboxPublisher, outboxDialect, dataSource, props);
	}

	@Bean
	@ConditionalOnMissingBean
	OutboxRecuperacao outboxRecuperacao(DataSource dataSource, SqlServerOutboxDialect outboxDialect,
			OutboxDespachante outboxDespachante, OutboxRelayProperties props) {
		return new OutboxRecuperacao(dataSource, outboxDialect, outboxDespachante, props);
	}

}
