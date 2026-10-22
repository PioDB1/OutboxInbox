package br.com.db1.poc.outbox.spring;

import org.springframework.context.SmartLifecycle;

import br.com.db1.poc.inbox.InboxConsumer;

/**
 * Inicia o {@link InboxConsumer} depois de os demais beans estarem prontos e o fecha antes deles, para que nenhuma
 * mensagem chegue a um tratador cujas dependências já foram encerradas.
 */
final class InboxConsumerLifecycle implements SmartLifecycle {

	private final InboxConsumer consumer;
	private volatile boolean running;

	InboxConsumerLifecycle(InboxConsumer consumer) {
		this.consumer = consumer;
	}

	@Override
	public void start() {
		consumer.start();
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		consumer.close();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

}
