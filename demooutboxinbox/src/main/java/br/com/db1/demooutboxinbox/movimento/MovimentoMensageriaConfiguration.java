package br.com.db1.demooutboxinbox.movimento;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import br.com.db1.poc.outbox.spring.OutboxRelayProperties;
import br.com.db1.poc.outbox.rabbit.RabbitMessagePublisher;
import br.com.db1.poc.outbox.rabbit.RabbitTopology;

/**
 * Fila do evento {@code MovimentoRegistrado}. Com {@code mandatory=true}, a publicação sem fila ligada à routing key
 * falha e reenvia indefinidamente, por isso a fila é declarada na inicialização, depois do exchange do publicador.
 */
@Configuration(proxyBeanMethods = false)
public class MovimentoMensageriaConfiguration {

	static final String TIPO_EVENTO = "MovimentoRegistrado";

	static final String FILA = "movimentos.movimento-registrado";

	public record FilaMovimentoRegistrado() {
	}

	@Bean
	FilaMovimentoRegistrado filaMovimentoRegistrado(ConnectionFactory outboxRabbitConnectionFactory,
			OutboxRelayProperties props, RabbitMessagePublisher outboxPublisher) throws IOException, TimeoutException {
		try (Connection connection = outboxRabbitConnectionFactory.newConnection();
				Channel channel = connection.createChannel()) {
			RabbitTopology.declareQuorumQueue(channel, FILA, props.exchange(), TIPO_EVENTO);
		}
		return new FilaMovimentoRegistrado();
	}

}
