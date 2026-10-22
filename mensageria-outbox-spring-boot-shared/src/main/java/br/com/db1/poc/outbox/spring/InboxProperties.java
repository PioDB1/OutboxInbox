package br.com.db1.poc.outbox.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import br.com.db1.poc.inbox.InboxConfig;

/**
 * Parâmetros do consumidor, nas chaves {@code inbox.consumer.*} da biblioteca {@code mensageria-outbox} e com os
 * mesmos padrões de {@link InboxConfig#fromProperties}. {@code queue} não tem padrão: sem ela o consumidor não é
 * criado. A DLQ assume {@code <queue>.dlq}.
 */
@ConfigurationProperties(prefix = "inbox.consumer")
public record InboxProperties(
		String queue,
		String deadLetterQueue,
		@DefaultValue("5") int maxAttempts,
		@DefaultValue Backoff backoff,
		@DefaultValue("50") int maxInFlight,
		@DefaultValue("8") int workers,
		@DefaultValue("5s") Duration confirmTimeout) {

	public record Backoff(
			@DefaultValue("200ms") Duration initial,
			@DefaultValue("2.0") double multiplier,
			@DefaultValue("30s") Duration max) {
	}

	/**
	 * Configuração do consumidor da biblioteca.
	 *
	 * @throws IllegalArgumentException valor recusado pela validação de {@link InboxConfig}
	 */
	InboxConfig toConfig() {
		String dlq = deadLetterQueue == null || deadLetterQueue.isBlank() ? queue + ".dlq" : deadLetterQueue;
		return new InboxConfig(queue, dlq, maxAttempts, backoff.initial(), backoff.multiplier(), backoff.max(),
				maxInFlight, workers, confirmTimeout);
	}

}
