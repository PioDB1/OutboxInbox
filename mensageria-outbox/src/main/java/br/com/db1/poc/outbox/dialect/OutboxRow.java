package br.com.db1.poc.outbox.dialect;

import br.com.db1.poc.outbox.envelope.Envelope;
import java.util.Objects;

/** Linha lida da outbox: o {@code seq}, o {@code rv} (rowversion de 8 bytes) e o envelope reconstruído. */
public final class OutboxRow {

    private final long seq;
    private final byte[] rv;
    private final Envelope envelope;

    /**
     * @param seq identificador crescente atribuído pelo banco
     * @param rv  valor da coluna {@code rowversion}, em big-endian
     */
    public OutboxRow(long seq, byte[] rv, Envelope envelope) {
        this.seq = seq;
        this.rv = rv;
        this.envelope = envelope;
    }

    public long seq() {
        return seq;
    }

    public byte[] rv() {
        return rv;
    }

    public Envelope envelope() {
        return envelope;
    }

    public String aggregateKey() {
        return envelope.aggregateKey();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxRow)) {
            return false;
        }
        OutboxRow that = (OutboxRow) other;
        return seq == that.seq && Objects.equals(rv, that.rv) && Objects.equals(envelope, that.envelope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, rv, envelope);
    }

    @Override
    public String toString() {
        return "OutboxRow[seq=" + seq + ", rv=" + rv + ", envelope=" + envelope + "]";
    }
}
