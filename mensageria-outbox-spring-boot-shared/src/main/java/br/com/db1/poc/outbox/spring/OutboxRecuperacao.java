package br.com.db1.poc.outbox.spring;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.OutboxRow;

/**
 * Na inicialização, entrega ao {@link OutboxDespachante} as linhas da outbox ainda não marcadas como enviadas
 * ({@code sent_at IS NULL}), em lotes de {@code outbox.relay.batch-size}, em uma thread própria que não bloqueia a
 * inicialização.
 */
public class OutboxRecuperacao implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(OutboxRecuperacao.class);

	private final DataSource dataSource;

	private final OutboxDialect dialect;

	private final OutboxDespachante despachante;

	private final int tamanhoDoLote;

	private volatile Thread execucao;

	public OutboxRecuperacao(DataSource dataSource, OutboxDialect dialect, OutboxDespachante despachante,
			OutboxRelayProperties props) {
		this.dataSource = dataSource;
		this.dialect = dialect;
		this.despachante = despachante;
		this.tamanhoDoLote = props.batchSize();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void iniciar() {
		Thread thread = new Thread(this::recuperar, "outbox-recuperacao");
		execucao = thread;
		thread.start();
	}

	/**
	 * Lê um lote, espera as suas linhas serem confirmadas e marcadas, em ordem de {@code seq}, e só então lê o
	 * próximo, porque {@code claimPending} sempre devolve as primeiras linhas pendentes. Uma linha confirmada cuja
	 * marcação falhou voltaria na leitura seguinte e retém as posteriores do mesmo {@code aggregateKey}; nesse caso a
	 * recuperação para sem esperar o restante do lote, e as pendentes ficam para a próxima inicialização.
	 */
	private void recuperar() {
		int recuperadas = 0;
		try {
			List<OutboxRow> lote;
			while (!(lote = lerPendentes()).isEmpty()) {
				List<CompletableFuture<Boolean>> envios = lote.stream()
						.map(linha -> despachante.despachar(linha.seq(), linha.envelope()))
						.toList();
				for (CompletableFuture<Boolean> envio : envios) {
					if (!envio.get()) {
						log.warn("Recuperação da outbox interrompida após {} linhas: houve linha confirmada sem "
								+ "marcação de sent_at; as pendentes restantes são publicadas na próxima inicialização",
								recuperadas);
						return;
					}
					recuperadas++;
				}
			}
			log.info("Recuperação da outbox concluída: {} linhas pendentes publicadas", recuperadas);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.info("Recuperação da outbox encerrada com a aplicação após {} linhas", recuperadas);
		} catch (ExecutionException e) {
			log.info("Recuperação da outbox encerrada com a aplicação após {} linhas", recuperadas);
		} catch (SQLException e) {
			log.error("Falha ao ler as linhas pendentes da outbox; confira se as tabelas da outbox foram criadas pelo "
					+ "script de esquema no banco da aplicação", e);
		}
	}

	/**
	 * Lê o lote e confirma a transação antes do despacho: {@code claimPending} usa {@code UPDLOCK, READPAST, ROWLOCK},
	 * e os bloqueios mantidos até o fim da transação impediriam a marcação de {@code sent_at} pelo despachante.
	 */
	private List<OutboxRow> lerPendentes() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				List<OutboxRow> linhas = dialect.claimPending(connection, tamanhoDoLote);
				connection.commit();
				return linhas;
			} catch (SQLException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	@Override
	public void destroy() {
		Thread atual = execucao;
		if (atual != null) {
			atual.interrupt();
		}
	}

}
