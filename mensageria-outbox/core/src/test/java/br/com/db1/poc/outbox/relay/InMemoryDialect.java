package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;
import br.com.db1.poc.outbox.envelope.Envelope;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Outbox em memória que reproduz o que os relays pedem ao SQL Server: linhas com {@code seq} e {@code rv}
 * crescentes, linhas de transações abertas que o claim ignora e a sonda enxerga, limite seguro dado pela menor
 * linha aberta, ponto de leitura e lease. Permite exercitar a lógica das duas estratégias sem Docker.
 */
final class InMemoryDialect implements OutboxDialect {

    static final class Stored {
        final long seq;
        final long rv;
        final Envelope envelope;
        final long createdAtMillis = System.currentTimeMillis();
        boolean sent;
        boolean open;

        Stored(long seq, long rv, Envelope envelope, boolean open) {
            this.seq = seq;
            this.rv = rv;
            this.envelope = envelope;
            this.open = open;
        }
    }

    final List<Stored> rows = new ArrayList<>();
    long readPoint;
    String leaseHolder;
    long leaseExpiresAtMillis;
    int commits;

    private long nextSeq = 1;

    /** Insere uma linha confirmada. */
    synchronized Stored add(Envelope envelope) {
        return add(envelope, false);
    }

    /** Insere uma linha de uma transação ainda aberta: o claim a ignora e o limite seguro fica abaixo dela. */
    synchronized Stored addOpen(Envelope envelope) {
        return add(envelope, true);
    }

    private Stored add(Envelope envelope, boolean open) {
        Stored stored = new Stored(nextSeq, nextSeq, envelope, open);
        nextSeq++;
        rows.add(stored);
        return stored;
    }

    synchronized void commit(Stored stored) {
        stored.open = false;
    }

    synchronized void rollback(Stored stored) {
        rows.remove(stored);
    }

    synchronized long pendingCount() {
        return rows.stream().filter(r -> !r.sent).count();
    }

    static byte[] bytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    static long value(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    /** Fonte de dados cujas conexões ignoram commit e rollback: o estado vive neste dialeto. */
    DataSource dataSource() {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return connection();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Connection connection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        commits++;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    @Override
    public synchronized long insert(Connection connection, Envelope envelope) {
        return add(envelope).seq;
    }

    @Override
    public synchronized List<OutboxRow> claimPending(Connection connection, int limit) {
        return rows.stream()
                .filter(r -> !r.sent && !r.open)
                .sorted(Comparator.comparingLong(r -> r.seq))
                .limit(limit)
                .map(r -> new OutboxRow(r.seq, bytes(r.rv), r.envelope))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean hasPendingBetween(Connection connection, String aggregateKey, long afterSeq,
            long beforeSeq) {
        return rows.stream().anyMatch(r -> !r.sent && r.envelope.aggregateKey().equals(aggregateKey)
                && r.seq > afterSeq && r.seq < beforeSeq);
    }

    @Override
    public synchronized void markSent(Connection connection, List<Long> seqs) {
        rows.stream().filter(r -> seqs.contains(r.seq)).forEach(r -> r.sent = true);
    }

    @Override
    public synchronized byte[] minActiveRowversion(Connection connection) {
        long min = rows.stream().filter(r -> r.open).mapToLong(r -> r.rv).min().orElse(nextSeq);
        return bytes(min);
    }

    @Override
    public synchronized byte[] readPoint(Connection connection) {
        return bytes(readPoint);
    }

    @Override
    public synchronized List<OutboxRow> readRange(Connection connection, byte[] afterExclusive,
            byte[] beforeExclusive, int limit) {
        long after = value(afterExclusive);
        long before = value(beforeExclusive);
        return rows.stream()
                .filter(r -> r.rv > after && r.rv < before)
                .sorted(Comparator.comparingLong(r -> r.rv))
                .limit(limit)
                .map(r -> new OutboxRow(r.seq, bytes(r.rv), r.envelope))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized void advanceReadPoint(Connection connection, byte[] rv) {
        readPoint = Math.max(readPoint, value(rv));
    }

    @Override
    public synchronized PendingSummary pendingUnsent(Connection connection) {
        return summarize(rows.stream().filter(r -> !r.sent && !r.open).collect(Collectors.toList()));
    }

    @Override
    public synchronized PendingSummary pendingAheadOfReadPoint(Connection connection) {
        return summarize(rows.stream().filter(r -> r.rv > readPoint && !r.open).collect(Collectors.toList()));
    }

    private static PendingSummary summarize(List<Stored> pending) {
        long oldest = pending.stream().mapToLong(r -> r.createdAtMillis).min().orElse(Long.MAX_VALUE);
        return new PendingSummary(pending.size(), pending.isEmpty() ? 0 : System.currentTimeMillis() - oldest);
    }

    @Override
    public synchronized boolean acquireOrRenewLease(Connection connection, String holderId, long durationMillis) {
        long now = System.currentTimeMillis();
        if (leaseHolder == null || leaseHolder.equals(holderId) || leaseExpiresAtMillis < now) {
            leaseHolder = holderId;
            leaseExpiresAtMillis = now + durationMillis;
            return true;
        }
        return false;
    }

    @Override
    public synchronized void releaseLease(Connection connection, String holderId) {
        if (holderId.equals(leaseHolder)) {
            leaseExpiresAtMillis = 0;
        }
    }

    @Override
    public int deleteSentByTimestamp(Connection connection, long maxAgeMillis, int batchSize) {
        return 0;
    }

    @Override
    public int deleteBehindReadPoint(Connection connection, long maxAgeMillis, int batchSize) {
        return 0;
    }

    static Envelope envelope(String aggregateKey) {
        return Envelope.novo(UUID.randomUUID(), "idem-" + UUID.randomUUID(), aggregateKey, "PagamentoRecebido",
                (short) 1, java.time.Instant.now(), new byte[] {1}, "trace");
    }
}
