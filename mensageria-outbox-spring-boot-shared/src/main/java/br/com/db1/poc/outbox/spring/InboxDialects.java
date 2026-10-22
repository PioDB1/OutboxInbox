package br.com.db1.poc.outbox.spring;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;

/** Escolhe o dialeto da inbox pelo produto do banco do {@link DataSource}. */
final class InboxDialects {

	static final String SQL_SERVER = "Microsoft SQL Server";
	static final String H2 = "H2";

	private InboxDialects() {
	}

	/**
	 * @throws IllegalStateException o produto do banco não é SQL Server nem H2, ou o banco está inacessível
	 */
	static InboxDialect detect(DataSource dataSource) {
		String product;
		try (Connection connection = dataSource.getConnection()) {
			product = connection.getMetaData().getDatabaseProductName();
		}
		catch (SQLException e) {
			throw new IllegalStateException("Não foi possível detectar o produto do banco da inbox: " + e.getMessage(),
					e);
		}
		if (SQL_SERVER.equals(product)) {
			return new SqlServerInboxDialect();
		}
		if (H2.equals(product)) {
			return new H2InboxDialect();
		}
		throw new IllegalStateException("Produto de banco não suportado pelo consumidor da inbox: '" + product
				+ "'. Produtos suportados: '" + SQL_SERVER + "' e '" + H2 + "'.");
	}

}
