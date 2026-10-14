package br.com.db1.poc.outbox.relay;

/**
 * Relay da outbox: lê as linhas pendentes e as publica no broker, considerando a linha enviada somente depois do
 * confirm positivo. As duas estratégias ({@link ClaimRelay} e {@link ReadPointRelay}) implementam esta interface.
 *
 * <p>Uma instância é de uso único: depois de {@link #close()} ela não executa mais ciclos.
 */
public interface Relay extends AutoCloseable {

    /** Inicia a thread do relay; sem efeito quando {@code outbox.relay.enabled=false}. */
    void start();

    /**
     * Executa um ciclo completo: lease, publicação de um lote e retenção.
     *
     * @return linhas publicadas no ciclo; zero quando o relay está desabilitado ou não detém o lease
     * @throws RelayException falha de acesso ao banco
     */
    int runOnce();

    /** Para a thread do relay e libera o lease. */
    @Override
    void close();
}
