package br.com.db1.poc.outbox.spring;

import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rabbitmq.client.ConnectionFactory;

/**
 * Fábrica do cliente RabbitMQ usada pela biblioteca, preenchida a partir de {@code spring.rabbitmq.*} (Spring Boot
 * 4.x, {@code RabbitProperties} do módulo {@code spring-boot-amqp}). A fábrica interna do
 * {@code CachingConnectionFactory} do Spring AMQP desliga a recuperação automática do cliente, que o contrato da
 * biblioteca exige ligada.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RabbitProperties.class)
class OutboxRabbitConnectionFactoryConfiguration {

	@Bean
	ConnectionFactory outboxRabbitConnectionFactory(RabbitProperties rabbit) {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(rabbit.determineHost());
		factory.setPort(rabbit.determinePort());
		factory.setUsername(rabbit.determineUsername());
		factory.setPassword(rabbit.determinePassword());
		if (rabbit.determineVirtualHost() != null) {
			factory.setVirtualHost(rabbit.determineVirtualHost());
		}
		factory.setAutomaticRecoveryEnabled(true);
		return factory;
	}

}
