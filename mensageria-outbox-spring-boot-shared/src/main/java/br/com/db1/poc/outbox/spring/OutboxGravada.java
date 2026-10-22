package br.com.db1.poc.outbox.spring;

import br.com.db1.poc.shared.envelope.Envelope;

/** Evento de aplicação de uma linha gravada na outbox, tratado depois do commit da transação que a gravou. */
public record OutboxGravada(long seq, Envelope envelope) {
}
