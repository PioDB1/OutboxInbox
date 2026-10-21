//package br.com.db1.poc.outbox;
//
//import static org.junit.jupiter.api.Assertions.assertArrayEquals;
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertNotEquals;
//import static org.junit.jupiter.api.Assertions.assertSame;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//import br.com.db1.poc.outbox.dialect.OutboxDialect;
//import br.com.db1.poc.outbox.dialect.OutboxRow;
//import br.com.db1.poc.shared.envelope.Envelope;
//import br.com.db1.poc.outbox.publish.PublishableMessage;
//import br.com.db1.poc.outbox.relay.PendingStats;
//import java.time.Duration;
//import java.time.Instant;
//import java.util.UUID;
//import org.junit.jupiter.api.Test;
//
///** Equivalência dos tipos de valor do módulo: acessores, validações, {@code equals}, {@code hashCode} e {@code toString}. */
//class ValueTypesTest {
//
//    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
//    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
//    private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T16:20:31.123456Z");
//
//    private static Envelope envelope() {
//        return new Envelope(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1", "PagamentoRecebido", (short) 3,
//                OCCURRED_AT, new byte[] {1, 2, 3}, "trace-1");
//    }
//
//    @Test
//    void publishableMessageFromCopiaCadaCampoDoEnvelope() {
//        Envelope envelope = envelope();
//
//        PublishableMessage message = PublishableMessage.from(envelope);
//
//        assertEquals(envelope.messageId(), message.messageId());
//        assertEquals(envelope.correlationId(), message.correlationId());
//        assertEquals(envelope.idempotencyKey(), message.idempotencyKey());
//        assertEquals(envelope.aggregateKey(), message.aggregateKey());
//        assertEquals(envelope.type(), message.type());
//        assertEquals(envelope.schemaVersion(), message.schemaVersion());
//        assertEquals(envelope.occurredAt(), message.occurredAt());
//        assertArrayEquals(envelope.payload(), message.payload());
//        assertEquals(envelope.traceId(), message.traceId());
//    }
//
//    @Test
//    void publishableMessageComparaComponentesComObjectsEquals() {
//        byte[] payload = {1, 2, 3};
//        PublishableMessage first = new PublishableMessage(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
//                "PagamentoRecebido", (short) 3, OCCURRED_AT, payload, "trace-1");
//        PublishableMessage same = new PublishableMessage(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
//                "PagamentoRecebido", (short) 3, OCCURRED_AT, payload, "trace-1");
//        PublishableMessage otherType = new PublishableMessage(MESSAGE_ID, CORRELATION_ID, "idem-1", "pagamento-1",
//                "PagamentoEstornado", (short) 3, OCCURRED_AT, payload, "trace-1");
//
//        assertEquals(first, same);
//        assertEquals(first.hashCode(), same.hashCode());
//        assertNotEquals(first, otherType);
//        assertNotEquals(first, null);
//        assertEquals("PublishableMessage[messageId=" + MESSAGE_ID + ", correlationId=" + CORRELATION_ID
//                + ", idempotencyKey=idem-1, aggregateKey=pagamento-1, type=PagamentoRecebido, schemaVersion=3, "
//                + "occurredAt=" + OCCURRED_AT + ", payload=" + payload + ", traceId=trace-1]", first.toString());
//    }
//
//    @Test
//    void outboxRowExpoeSeqRvEnvelopeEAggregateKey() {
//        byte[] rv = {0, 0, 0, 0, 0, 0, 0, 7};
//        Envelope envelope = envelope();
//
//        OutboxRow row = new OutboxRow(42L, rv, envelope);
//
//        assertEquals(42L, row.seq());
//        assertSame(rv, row.rv());
//        assertSame(envelope, row.envelope());
//        assertEquals("pagamento-1", row.aggregateKey());
//    }
//
//    @Test
//    void outboxRowComparaComponentesComObjectsEquals() {
//        byte[] rv = {0, 0, 0, 0, 0, 0, 0, 7};
//        OutboxRow row = new OutboxRow(42L, rv, envelope());
//
//        assertEquals(row, new OutboxRow(42L, rv, envelope()));
//        assertEquals(row.hashCode(), new OutboxRow(42L, rv, envelope()).hashCode());
//        assertNotEquals(row, new OutboxRow(43L, rv, envelope()));
//        assertNotEquals(row, new OutboxRow(42L, new byte[8], envelope()));
//        assertEquals("OutboxRow[seq=42, rv=" + rv + ", envelope=" + envelope() + "]", row.toString());
//    }
//
//    @Test
//    void pendingStatsExpoeContagemEIdadeDaMaisAntiga() {
//        PendingStats stats = new PendingStats(5, Duration.ofSeconds(12));
//
//        assertEquals(5, stats.count());
//        assertEquals(Duration.ofSeconds(12), stats.oldestAge());
//    }
//
//    @Test
//    void pendingStatsAceitaZeroPendentes() {
//        PendingStats stats = new PendingStats(0, Duration.ZERO);
//
//        assertEquals(0, stats.count());
//        assertEquals(Duration.ZERO, stats.oldestAge());
//    }
//
//    @Test
//    void pendingStatsRejeitaContagemNegativaEIdadeNulaOuNegativa() {
//        assertThrows(IllegalArgumentException.class, () -> new PendingStats(-1, Duration.ZERO));
//        assertThrows(IllegalArgumentException.class, () -> new PendingStats(0, null));
//        assertThrows(IllegalArgumentException.class, () -> new PendingStats(0, Duration.ofMillis(-1)));
//    }
//
//    @Test
//    void pendingStatsComparaComponentesEImprimeNoFormatoDoRegistro() {
//        PendingStats stats = new PendingStats(5, Duration.ofSeconds(12));
//
//        assertEquals(stats, new PendingStats(5, Duration.ofSeconds(12)));
//        assertEquals(stats.hashCode(), new PendingStats(5, Duration.ofSeconds(12)).hashCode());
//        assertNotEquals(stats, new PendingStats(6, Duration.ofSeconds(12)));
//        assertNotEquals(stats, new PendingStats(5, Duration.ofSeconds(13)));
//        assertEquals("PendingStats[count=5, oldestAge=PT12S]", stats.toString());
//    }
//
//    @Test
//    void pendingSummaryExpoeComponentesEComparaPorValor() {
//        OutboxDialect.PendingSummary summary = new OutboxDialect.PendingSummary(4, 1500);
//
//        assertEquals(4, summary.count());
//        assertEquals(1500, summary.oldestAgeMillis());
//        assertEquals(summary, new OutboxDialect.PendingSummary(4, 1500));
//        assertEquals(summary.hashCode(), new OutboxDialect.PendingSummary(4, 1500).hashCode());
//        assertNotEquals(summary, new OutboxDialect.PendingSummary(4, 1501));
//        assertEquals("PendingSummary[count=4, oldestAgeMillis=1500]", summary.toString());
//    }
//}
