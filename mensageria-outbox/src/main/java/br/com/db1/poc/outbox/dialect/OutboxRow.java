package br.com.db1.poc.outbox.dialect;

import br.com.db1.poc.outbox.envelope.Envelope;

/**
 * Linha lida da outbox: o {@code seq}, o {@code rv} (rowversion de 8 bytes) e o envelope reconstruído.
 *
 * @param seq identificador crescente atribuído pelo banco
 * @param rv  valor da coluna {@code rowversion}, em big-endian
 */
public record OutboxRow(long seq, byte[] rv, Envelope envelope) {

    public String aggregateKey() {
        return envelope.aggregateKey();
    }
}
