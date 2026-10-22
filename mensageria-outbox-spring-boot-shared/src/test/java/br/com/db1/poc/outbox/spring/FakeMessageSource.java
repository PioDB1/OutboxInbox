package br.com.db1.poc.outbox.spring;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.inbox.MessageSource;

/** {@link MessageSource} simulada dos testes: guarda os parâmetros do {@code start} e entrega mensagens sob demanda. */
final class FakeMessageSource implements MessageSource {

	private final List<Delivery> acked = new CopyOnWriteArrayList<>();
	private final List<String> deadLettered = new CopyOnWriteArrayList<>();

	private volatile Consumer<Delivery> onDelivery;
	private volatile String queue;
	private volatile String deadLetterQueue;
	private volatile int maxInFlight;
	private volatile boolean closed;

	@Override
	public void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery) {
		this.queue = queue;
		this.deadLetterQueue = deadLetterQueue;
		this.maxInFlight = maxInFlight;
		this.onDelivery = onDelivery;
	}

	@Override
	public void ack(Delivery delivery) {
		acked.add(delivery);
	}

	@Override
	public void requeue(Delivery delivery) {
	}

	@Override
	public void deadLetter(Delivery delivery, String failure, int attempts) {
		deadLettered.add(failure);
	}

	@Override
	public void close() {
		closed = true;
	}

	void deliver(Delivery delivery) {
		onDelivery.accept(delivery);
	}

	boolean started() {
		return onDelivery != null;
	}

	boolean closed() {
		return closed;
	}

	String queue() {
		return queue;
	}

	String deadLetterQueue() {
		return deadLetterQueue;
	}

	int maxInFlight() {
		return maxInFlight;
	}

	List<Delivery> acked() {
		return acked;
	}

	List<String> deadLettered() {
		return deadLettered;
	}

	/** Mensagem com os oito headers do contrato; o {@code messageId} e a chave variam por chamada. */
	static Delivery message(String aggregateKey) {
		UUID messageId = UUID.randomUUID();
		Map<String, Object> headers = new HashMap<>();
		headers.put("messageId", messageId.toString());
		headers.put("correlationId", UUID.randomUUID().toString());
		headers.put("idempotencyKey", "idem-" + messageId);
		headers.put("aggregateKey", aggregateKey);
		headers.put("type", "PagamentoRecebido");
		headers.put("schemaVersion", 1);
		headers.put("occurredAt", Instant.now().toString());
		headers.put("traceId", "trace-" + messageId);
		byte[] body = { 1, 2, 3 };
		return new Delivery() {

			@Override
			public Map<String, Object> headers() {
				return headers;
			}

			@Override
			public byte[] body() {
				return body;
			}

		};
	}

}
