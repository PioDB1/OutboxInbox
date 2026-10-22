package br.com.db1.poc.outbox.spring;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import com.rabbitmq.client.ConnectionFactory;

import br.com.db1.poc.inbox.BlockedKeys;
import br.com.db1.poc.inbox.InMemoryBlockedKeys;
import br.com.db1.poc.inbox.InboxConsumer;
import br.com.db1.poc.inbox.InboxMessageHandler;
import br.com.db1.poc.inbox.InboxTransactionExecutor;
import br.com.db1.poc.inbox.MessageSource;
import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.rabbit.RabbitMessageSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Integra o consumidor da biblioteca {@code mensageria-outbox} ao Spring Boot: o {@link InboxConsumer} é iniciado e
 * parado pelo ciclo de vida do Spring e executa cada mensagem em uma transação Spring, junto do registro na inbox.
 * <p>
 * Nada é registrado sem {@code inbox.consumer.queue} e sem um bean {@link InboxMessageHandler}: a aplicação só com a
 * outbox sobe sem os beans do consumidor. A tabela {@code inbox} é criada por script de esquema executado no banco
 * antes de subir a aplicação; a auto-configuração não chama o {@code InboxSchemaInstaller}. A fila principal e a DLQ
 * são declaradas pela aplicação; o consumidor falha a subida se alguma não existir.
 * <p>
 * A fábrica de conexões do RabbitMQ ({@code outboxRabbitConnectionFactory}) vem de
 * {@link OutboxRabbitConnectionFactoryConfiguration}, a mesma da {@link OutboxAutoConfiguration}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "inbox.consumer", name = "queue")
@ConditionalOnBean(InboxMessageHandler.class)
@EnableConfigurationProperties(InboxProperties.class)
@Import(OutboxRabbitConnectionFactoryConfiguration.class)
public class InboxAutoConfiguration {

	/** Dialeto escolhido pelo produto do banco: SQL Server ou H2; outro produto falha a subida. */
	@Bean
	@ConditionalOnMissingBean
	InboxDialect inboxDialect(DataSource dataSource) {
		return InboxDialects.detect(dataSource);
	}

	@Bean
	@ConditionalOnMissingBean
	InboxTransactionExecutor inboxTransactionExecutor(PlatformTransactionManager transactionManager,
			DataSource dataSource) {
		return new SpringInboxTransactionExecutor(transactionManager, dataSource);
	}

	/** Contadores no {@code MeterRegistry} da aplicação, ou em um registro simples quando ela não tem um. */
	@Bean
	@ConditionalOnMissingBean
	InboxMetrics inboxMetrics(ObjectProvider<MeterRegistry> registry) {
		return new InboxMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
	}

	@Bean
	@ConditionalOnMissingBean
	BlockedKeys inboxBlockedKeys() {
		return new InMemoryBlockedKeys();
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	MessageSource inboxMessageSource(
			@Qualifier("outboxRabbitConnectionFactory") ConnectionFactory outboxRabbitConnectionFactory,
			InboxProperties props) {
		return new RabbitMessageSource(outboxRabbitConnectionFactory, props.confirmTimeout());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	InboxConsumer inboxConsumer(InboxProperties props, MessageSource inboxMessageSource, InboxDialect inboxDialect,
			InboxTransactionExecutor inboxTransactionExecutor, InboxMessageHandler handler,
			BlockedKeys inboxBlockedKeys, InboxMetrics inboxMetrics) {
		return new InboxConsumer(props.toConfig(), inboxMessageSource, inboxDialect, inboxTransactionExecutor,
				handler, inboxBlockedKeys, inboxMetrics);
	}

	@Bean
	@ConditionalOnMissingBean
	InboxConsumerLifecycle inboxConsumerLifecycle(InboxConsumer inboxConsumer) {
		return new InboxConsumerLifecycle(inboxConsumer);
	}

}
