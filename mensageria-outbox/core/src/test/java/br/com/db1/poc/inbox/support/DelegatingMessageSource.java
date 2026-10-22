package br.com.db1.poc.inbox.support;

import br.com.db1.poc.inbox.Delivery;
import br.com.db1.poc.inbox.MessageSource;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * {@link MessageSource} que repassa tudo a outra e oferece ganchos de observação: {@link #onReceived} em cada
 * entrega recebida e {@link #beforeAck} antes de cada ack.
 */
public class DelegatingMessageSource implements MessageSource {

    private final MessageSource delegate;

    public DelegatingMessageSource(MessageSource delegate) {
        this.delegate = delegate;
    }

    protected void onReceived(Delivery delivery) {
    }

    protected void beforeAck(Delivery delivery) {
    }

    @Override
    public void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery)
            throws IOException {
        delegate.start(queue, deadLetterQueue, maxInFlight, delivery -> {
            onReceived(delivery);
            onDelivery.accept(delivery);
        });
    }

    @Override
    public void ack(Delivery delivery) throws IOException {
        beforeAck(delivery);
        delegate.ack(delivery);
    }

    @Override
    public void requeue(Delivery delivery) throws IOException {
        delegate.requeue(delivery);
    }

    @Override
    public void deadLetter(Delivery delivery, String failure, int attempts) throws IOException {
        delegate.deadLetter(delivery, failure, attempts);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
