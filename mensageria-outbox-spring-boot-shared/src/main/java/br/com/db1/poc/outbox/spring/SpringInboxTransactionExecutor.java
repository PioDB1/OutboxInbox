package br.com.db1.poc.outbox.spring;

import java.sql.Connection;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.db1.poc.inbox.InboxTransactionExecutor;

/**
 * Executa o trabalho da inbox em uma transação Spring: o tratador, o registro na inbox e qualquer acesso
 * transacional da aplicação (JPA, {@code JdbcTemplate}) participam da mesma transação. A conexão entregue ao
 * trabalho é a da transação, obtida por {@link DataSourceUtils}.
 */
public final class SpringInboxTransactionExecutor implements InboxTransactionExecutor {

	private final TransactionTemplate transaction;
	private final DataSource dataSource;

	public SpringInboxTransactionExecutor(PlatformTransactionManager transactionManager, DataSource dataSource) {
		this.transaction = new TransactionTemplate(transactionManager);
		this.dataSource = dataSource;
	}

	@Override
	public void execute(TransactionWork work) throws Exception {
		try {
			transaction.execute(status -> {
				Connection connection = DataSourceUtils.getConnection(dataSource);
				try {
					work.run(connection);
					return null;
				}
				catch (Exception e) {
					throw new WorkFailure(e);
				}
				finally {
					DataSourceUtils.releaseConnection(connection, dataSource);
				}
			});
		}
		catch (WorkFailure failure) {
			throw failure.original;
		}
	}

	/** Leva a exceção do trabalho até fora do {@code TransactionTemplate}, que desfaz a transação ao recebê-la. */
	private static final class WorkFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient Exception original;

		WorkFailure(Exception original) {
			super(original);
			this.original = original;
		}

	}

}
