package br.com.db1.poc.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transação Spring do consumidor sobre H2 em memória: commit, rollback e a conexão compartilhada com o Spring. */
class SpringInboxTransactionExecutorTest {

	private DataSource dataSource;
	private JdbcTemplate jdbc;
	private SpringInboxTransactionExecutor executor;

	@BeforeEach
	void prepara() {
		dataSource = new DriverManagerDataSource("jdbc:h2:mem:executor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE efeito (id BIGINT AUTO_INCREMENT PRIMARY KEY, valor VARCHAR(50) NOT NULL)");
		executor = new SpringInboxTransactionExecutor(new DataSourceTransactionManager(dataSource), dataSource);
	}

	private int efeitos() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM efeito", Integer.class);
	}

	private static void insere(Connection connection, String valor) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("INSERT INTO efeito (valor) VALUES (?)")) {
			statement.setString(1, valor);
			statement.executeUpdate();
		}
	}

	@Test
	void confirmaQuandoOTrabalhoRetorna() throws Exception {
		executor.execute(connection -> insere(connection, "a"));

		assertThat(efeitos()).isEqualTo(1);
	}

	@Test
	void desfazEPropagaAExcecaoOriginalQuandoOTrabalhoLanca() {
		IOException falha = new IOException("falha do trabalho");

		assertThatThrownBy(() -> executor.execute(connection -> {
			insere(connection, "a");
			throw falha;
		})).isSameAs(falha);

		assertThat(efeitos()).isZero();
	}

	@Test
	void desfazEPropagaExcecaoNaoVerificada() {
		IllegalStateException falha = new IllegalStateException("falha do trabalho");

		assertThatThrownBy(() -> executor.execute(connection -> {
			insere(connection, "a");
			throw falha;
		})).isSameAs(falha);

		assertThat(efeitos()).isZero();
	}

	/** TC-39: a transação está ativa, sem autocommit, e a conexão do trabalho é a que o Spring usa. */
	@Test
	void trabalhoExecutaNaTransacaoDoSpringComAMesmaConexao() throws Exception {
		AtomicReference<Boolean> ativa = new AtomicReference<>();
		AtomicReference<Boolean> semAutocommit = new AtomicReference<>();
		AtomicReference<Boolean> mesmaConexao = new AtomicReference<>();

		executor.execute(connection -> {
			ativa.set(TransactionSynchronizationManager.isActualTransactionActive());
			semAutocommit.set(!connection.getAutoCommit());
			mesmaConexao.set(DataSourceUtils.getConnection(dataSource) == connection
					|| DataSourceUtils.isConnectionTransactional(connection, dataSource));
		});

		assertThat(ativa.get()).isTrue();
		assertThat(semAutocommit.get()).isTrue();
		assertThat(mesmaConexao.get()).isTrue();
	}

	@Test
	void jdbcTemplateParticipaDaMesmaTransacao() {
		assertThatThrownBy(() -> executor.execute(connection -> {
			insere(connection, "pela-conexao");
			jdbc.update("INSERT INTO efeito (valor) VALUES (?)", "pelo-template");
			throw new IllegalStateException("desfaz tudo");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(efeitos()).isZero();
	}

	@Test
	void jdbcTemplateConfirmaJuntoDaConexao() throws Exception {
		executor.execute(connection -> {
			insere(connection, "pela-conexao");
			jdbc.update("INSERT INTO efeito (valor) VALUES (?)", "pelo-template");
		});

		assertThat(efeitos()).isEqualTo(2);
	}

}
