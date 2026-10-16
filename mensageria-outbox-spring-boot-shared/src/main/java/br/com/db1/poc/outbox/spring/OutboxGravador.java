package br.com.db1.poc.outbox.spring;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.write.OutboxWriter;

/**
 * Ponto de entrada da aplicação: grava o envelope na outbox dentro da transação do dado de negócio e agenda a
 * publicação para depois do commit.
 */
public class OutboxGravador {

	private final OutboxWriter writer;

	private final DataSource dataSource;

	private final ApplicationEventPublisher eventos;

	private final SQLExceptionSubclassTranslator tradutor = new SQLExceptionSubclassTranslator();

	public OutboxGravador(OutboxWriter writer, DataSource dataSource, ApplicationEventPublisher eventos) {
		this.writer = writer;
		this.dataSource = dataSource;
		this.eventos = eventos;
	}

	/**
	 * Grava o envelope na outbox pela conexão JDBC da transação Spring em curso e agenda a publicação para depois do
	 * commit. A conexão é a que o gerenciador de transação expõe para o {@code DataSource}: a do
	 * {@code DataSourceTransactionManager} ou, com JPA, a da sessão Hibernate do {@code JpaTransactionManager}. Quem
	 * chama grava o dado de negócio antes, com {@code saveAndFlush} ou {@code flush} quando usa JPA, para que uma
	 * violação de restrição falhe antes da gravação da outbox.
	 * <p>
	 * Exige uma transação aberta: chamado fora dela, lança {@link IllegalTransactionStateException}, um erro de
	 * programação (falta de {@code @Transactional} em quem chama). Uma conexão que não pertence à transação chega em
	 * autocommit, e o {@link OutboxWriter} a recusa com {@link IllegalStateException}.
	 *
	 * @return o {@code seq} atribuído pelo banco
	 */
	public long gravar(Envelope envelope) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalTransactionStateException(
					"A gravação na outbox exige uma transação aberta (@Transactional em quem chama)");
		}
		Connection connection = DataSourceUtils.getConnection(dataSource);
		long seq;
		try {
			seq = writer.insert(connection, envelope);
		} catch (SQLException e) {
			DataAccessException traduzida = tradutor.translate("gravação na outbox", null, e);
			throw traduzida != null ? traduzida : new UncategorizedSQLException("gravação na outbox", null, e);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
		eventos.publishEvent(new OutboxGravada(seq, envelope));
		return seq;
	}

}
