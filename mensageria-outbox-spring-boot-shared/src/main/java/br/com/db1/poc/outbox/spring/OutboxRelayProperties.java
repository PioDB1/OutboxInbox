package br.com.db1.poc.outbox.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros de publicação, nas chaves {@code outbox.relay.*} da biblioteca {@code mensageria-outbox} e com os
 * mesmos padrões. {@code outbox.relay.enabled} não é lida aqui: o relay por polling da biblioteca não é instanciado
 * pela auto-configuração.
 */
@ConfigurationProperties(prefix = "outbox.relay")
public record OutboxRelayProperties(
		@DefaultValue("mensageria.outbox") String exchange,
		@DefaultValue("5s") Duration confirmTimeout,
		@DefaultValue Backoff backoff,
		@DefaultValue("100") int batchSize) {

	public record Backoff(
			@DefaultValue("200ms") Duration initial,
			@DefaultValue("2.0") double multiplier,
			@DefaultValue("30s") Duration max) {
	}

}
