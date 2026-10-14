package br.com.db1.poc.outbox.support;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Publicador de teste: registra as tentativas, falha onde o teste manda e aceita ganchos antes e depois da
 * publicação (por exemplo, lançar depois do confirm ou congelar a thread). Sem delegado, entrega em memória.
 */
public final class ScriptedPublisher implements MessagePublisher {

    /** Tentativa de publicação, com o instante em {@link System#nanoTime()}. */
    public record Attempt(PublishableMessage message, long nanoTime, boolean succeeded) {
    }

    private final MessagePublisher delegate;
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final List<PublishableMessage> delivered = new CopyOnWriteArrayList<>();

    private volatile Predicate<PublishableMessage> failWhen = message -> false;
    private volatile PublishException.Kind failKind = PublishException.Kind.MESSAGE;
    private volatile Consumer<PublishableMessage> beforeHook = message -> { };
    private volatile Consumer<PublishableMessage> afterHook = message -> { };

    public ScriptedPublisher() {
        this(null);
    }

    public ScriptedPublisher(MessagePublisher delegate) {
        this.delegate = delegate;
    }

    public ScriptedPublisher failWhen(Predicate<PublishableMessage> condition) {
        this.failWhen = condition;
        return this;
    }

    public ScriptedPublisher failAll() {
        return failWhen(message -> true);
    }

    public ScriptedPublisher failWith(PublishException.Kind kind) {
        this.failKind = kind;
        return this;
    }

    public ScriptedPublisher stopFailing() {
        return failWhen(message -> false);
    }

    /** Executado antes de cada tentativa. */
    public ScriptedPublisher before(Consumer<PublishableMessage> hook) {
        this.beforeHook = hook;
        return this;
    }

    /** Executado depois de cada publicação bem-sucedida e confirmada. */
    public ScriptedPublisher after(Consumer<PublishableMessage> hook) {
        this.afterHook = hook;
        return this;
    }

    @Override
    public void publish(PublishableMessage message) throws PublishException {
        beforeHook.accept(message);
        long now = System.nanoTime();
        if (failWhen.test(message)) {
            attempts.add(new Attempt(message, now, false));
            throw new PublishException("falha simulada", null, failKind);
        }
        try {
            if (delegate != null) {
                delegate.publish(message);
            }
        } catch (PublishException e) {
            attempts.add(new Attempt(message, now, false));
            throw e;
        }
        attempts.add(new Attempt(message, now, true));
        delivered.add(message);
        afterHook.accept(message);
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public List<Attempt> attemptsOf(java.util.UUID messageId) {
        return attempts.stream().filter(a -> a.message().messageId().equals(messageId)).toList();
    }

    public List<PublishableMessage> delivered() {
        return List.copyOf(delivered);
    }

    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
        }
    }
}
