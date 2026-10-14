package br.com.db1.poc.outbox.dialect;

import br.com.db1.poc.outbox.envelope.Envelope;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Acesso a banco da outbox, do lease e do ponto de leitura. Todo SQL do módulo vive nas implementações desta
 * interface; as operações usam a conexão e a transação do chamador e não fazem commit nem rollback.
 */
public interface OutboxDialect {

    /**
     * Linhas pendentes vistas por uma consulta de leitura.
     *
     * @param count          quantidade de linhas pendentes
     * @param oldestAgeMillis idade, no relógio do banco, da linha pendente mais antiga; zero sem pendentes
     */
    record PendingSummary(long count, long oldestAgeMillis) {
    }

    /** Insere a linha da outbox e devolve o {@code seq} atribuído. */
    long insert(Connection connection, Envelope envelope) throws SQLException;

    // Estratégia claim

    /**
     * Reserva até {@code limit} linhas pendentes por {@code seq}, ignorando as bloqueadas por outras transações.
     * Os bloqueios duram até o fim da transação da conexão.
     */
    List<OutboxRow> claimPending(Connection connection, int limit) throws SQLException;

    /**
     * Indica se existe linha pendente de {@code aggregateKey} com {@code seq} entre os limites exclusivos,
     * inclusive linha ainda não confirmada ou reservada por outra transação.
     */
    boolean hasPendingBetween(Connection connection, String aggregateKey, long afterSeq, long beforeSeq)
            throws SQLException;

    /** Marca as linhas como enviadas, no relógio do banco. */
    void markSent(Connection connection, List<Long> seqs) throws SQLException;

    // Estratégia de ponto de leitura

    /** Menor {@code rowversion} ainda ativo no banco; sem transações abertas, o próximo valor a ser atribuído. */
    byte[] minActiveRowversion(Connection connection) throws SQLException;

    /** Último {@code rv} do prefixo contíguo publicado; oito bytes zero quando ainda não há ponto gravado. */
    byte[] readPoint(Connection connection) throws SQLException;

    /** Lê até {@code limit} linhas com {@code afterExclusive < rv < beforeExclusive}, em ordem de {@code rv}. */
    List<OutboxRow> readRange(Connection connection, byte[] afterExclusive, byte[] beforeExclusive, int limit)
            throws SQLException;

    /** Avança o ponto de leitura para {@code rv}; nunca o faz retroceder. */
    void advanceReadPoint(Connection connection, byte[] rv) throws SQLException;

    // Pendentes (somente leitura)

    /**
     * Linhas com {@code sent_at IS NULL} já confirmadas; ignora, sem esperar, as linhas bloqueadas por transações
     * abertas. Representa as pendentes da estratégia claim.
     */
    PendingSummary pendingUnsent(Connection connection) throws SQLException;

    /**
     * Linhas com {@code rv} acima do ponto de leitura já confirmadas; ignora, sem esperar, as linhas bloqueadas por
     * transações abertas. Representa as pendentes da estratégia de ponto de leitura.
     */
    PendingSummary pendingAheadOfReadPoint(Connection connection) throws SQLException;

    // Lease

    /**
     * Adquire o lease livre ou expirado, ou renova o lease que já pertence a {@code holderId}, com expiração
     * {@code durationMillis} adiante no relógio do banco.
     *
     * @return {@code true} quando {@code holderId} detém o lease depois da chamada
     */
    boolean acquireOrRenewLease(Connection connection, String holderId, long durationMillis) throws SQLException;

    /** Expira imediatamente o lease de {@code holderId}, se for o detentor. */
    void releaseLease(Connection connection, String holderId) throws SQLException;

    // Retenção

    /** Remove até {@code batchSize} linhas com {@code sent_at} anterior a {@code maxAgeMillis}; devolve a contagem. */
    int deleteSentByTimestamp(Connection connection, long maxAgeMillis, int batchSize) throws SQLException;

    /**
     * Remove até {@code batchSize} linhas com {@code rv} até o ponto de leitura e {@code created_at} anterior a
     * {@code maxAgeMillis}; devolve a contagem.
     */
    int deleteBehindReadPoint(Connection connection, long maxAgeMillis, int batchSize) throws SQLException;
}
