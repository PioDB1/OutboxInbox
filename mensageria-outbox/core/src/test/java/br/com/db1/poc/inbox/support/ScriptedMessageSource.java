package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.inbox.MessageSource;
import br.com.db1.poc.inbox.StaleDeliveryException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link MessageSource} simulada: guarda os parâmetros do {@code start}, registra {@code ack}, {@code requeue} e
 * {@code deadLetter} na ordem em que ocorrem e injeta falhas de ack e de publicação na DLQ.
 */
public final class ScriptedMessageSource implements MessageSource {

    /** Publicação na DLQ registrada. */
    public static final class DeadLettered {

        private final Delivery delivery;
        private final String failure;
        private final int attempts;

        DeadLettered(Delivery delivery, String failure, int attempts) {
            this.delivery = delivery;
            this.failure = failure;
            this.attempts = attempts;
        }

        public Delivery delivery() {
            return delivery;
        }

        public String failure() {
            return failure;
        }

        public int attempts() {
            return attempts;
        }
    }

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Delivery> acked = new CopyOnWriteArrayList<>();
    private final List<Delivery> requeued = new CopyOnWriteArrayList<>();
    private final List<DeadLettered> deadLettered = new CopyOnWriteArrayList<>();
    private final Queue<IOException> ackFailures = new ArrayDeque<>();
    private final Queue<IOException> deadLetterFailures = new ArrayDeque<>();

    private volatile Consumer<Delivery> onDelivery;
    private volatile Consumer<Delivery> beforeAck = delivery -> { };
    private volatile String queue;
    private volatile String deadLetterQueue;
    private volatile int maxInFlight;
    private volatile boolean closed;
    private volatile boolean stale;
    private volatile int deadLetterCalls;

    @Override
    public void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery) {
        this.queue = queue;
        this.deadLetterQueue = deadLetterQueue;
        this.maxInFlight = maxInFlight;
        this.onDelivery = onDelivery;
        events.add("start");
    }

    /** Entrega a mensagem ao consumidor, como o broker faz. */
    public void deliver(Delivery delivery) {
        onDelivery.accept(delivery);
    }

    /** Executado no início de cada {@code ack}, antes de o ack ser registrado. */
    public void beforeAck(Consumer<Delivery> hook) {
        this.beforeAck = hook;
    }

    /** Os próximos {@code count} acks lançam {@link IOException}. */
    public synchronized void failNextAcks(int count) {
        for (int i = 0; i < count; i++) {
            ackFailures.add(new IOException("ack simulado falhou"));
        }
    }

    /** As próximas {@code count} publicações na DLQ lançam {@link IOException}. */
    public synchronized void failNextDeadLetters(int count) {
        for (int i = 0; i < count; i++) {
            deadLetterFailures.add(new IOException("publicação na DLQ simulada falhou"));
        }
    }

    /** Daqui em diante {@code ack} e {@code deadLetter} lançam {@link StaleDeliveryException}. */
    public void markStale() {
        this.stale = true;
    }

    @Override
    public void ack(Delivery delivery) throws IOException {
        beforeAck.accept(delivery);
        if (stale) {
            events.add("ack-stale");
            throw new StaleDeliveryException("entrega simulada perdida");
        }
        synchronized (this) {
            IOException failure = ackFailures.poll();
            if (failure != null) {
                events.add("ack-failed");
                throw failure;
            }
        }
        acked.add(delivery);
        events.add("ack");
    }

    @Override
    public void requeue(Delivery delivery) {
        requeued.add(delivery);
        events.add("requeue");
    }

    @Override
    public void deadLetter(Delivery delivery, String failure, int attempts) throws IOException {
        if (stale) {
            events.add("dlq-stale");
            throw new StaleDeliveryException("entrega simulada perdida");
        }
        synchronized (this) {
            deadLetterCalls++;
            IOException error = deadLetterFailures.poll();
            if (error != null) {
                events.add("dlq-failed");
                throw error;
            }
        }
        deadLettered.add(new DeadLettered(delivery, failure, attempts));
        events.add("dlq:" + failure);
    }

    @Override
    public void close() {
        closed = true;
        events.add("close");
    }

    public List<String> events() {
        return events;
    }

    public List<Delivery> acked() {
        return acked;
    }

    public List<Delivery> requeued() {
        return requeued;
    }

    public List<DeadLettered> deadLettered() {
        return deadLettered;
    }

    public synchronized int deadLetterCalls() {
        return deadLetterCalls;
    }

    public String queue() {
        return queue;
    }

    public String deadLetterQueue() {
        return deadLetterQueue;
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    public boolean closed() {
        return closed;
    }
}
