package br.com.db1.poc.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.db1.poc.inbox.BlockedKeys;
import br.com.db1.poc.inbox.InMemoryBlockedKeys;
import br.com.db1.poc.inbox.InboxConsumer;
import br.com.db1.poc.inbox.InboxMessageHandler;
import br.com.db1.poc.inbox.InboxTransactionExecutor;
import br.com.db1.poc.inbox.JdbcInboxTransactionExecutor;
import br.com.db1.poc.inbox.MessageSource;
import br.com.db1.poc.inbox.dialect.InboxDialect;
import br.com.db1.poc.inbox.dialect.h2.H2InboxDialect;
import br.com.db1.poc.inbox.dialect.sqlserver.SqlServerInboxDialect;
import br.com.db1.poc.inbox.metrics.InboxMetrics;
import br.com.db1.poc.inbox.schema.InboxSchemaInstaller;
import br.com.db1.poc.shared.envelope.Envelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Sobe a auto-configuração do consumidor com H2 em memória e uma {@link MessageSource} simulada, sem broker:
 * condições de criação, padrões das chaves {@code inbox.consumer.*}, beans da aplicação preservados, produto de
 * banco não suportado, ciclo de vida e transação Spring do tratador.
 */
class InboxAutoConfigurationTest {

	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
			.withUserConfiguration(BancoH2.class, FonteSimulada.class, Tratador.class)
			.withPropertyValues("inbox.consumer.queue=fila.teste", "inbox.consumer.backoff.initial=20ms",
					"inbox.consumer.backoff.max=50ms");

	private static void aguarda(String descricao, BooleanSupplier condicao) throws InterruptedException {
		long limite = System.nanoTime() + 15_000_000_000L;
		while (!condicao.getAsBoolean()) {
			assertThat(System.nanoTime()).as(descricao).isLessThan(limite);
			Thread.sleep(20);
		}
	}

	/** TC-35: com a fila e o tratador, o consumidor existe, é iniciado e entrega o envelope ao tratador. */
	@Test
	void criaOConsumidorIniciadoComFilaETratador() {
		FakeMessageSource[] fonte = new FakeMessageSource[1];
		contexto.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(InboxConsumer.class);
			assertThat(context).hasSingleBean(InboxDialect.class);
			assertThat(context.getBean(InboxDialect.class)).isInstanceOf(H2InboxDialect.class);
			assertThat(context).hasSingleBean(InboxTransactionExecutor.class);
			assertThat(context.getBean(InboxTransactionExecutor.class))
					.isInstanceOf(SpringInboxTransactionExecutor.class);
			assertThat(context).hasSingleBean(InboxMetrics.class);
			assertThat(context).hasSingleBean(BlockedKeys.class);
			fonte[0] = context.getBean(FakeMessageSource.class);
			assertThat(fonte[0].started()).isTrue();
			assertThat(fonte[0].queue()).isEqualTo("fila.teste");
			assertThat(fonte[0].deadLetterQueue()).isEqualTo("fila.teste.dlq");
			assertThat(fonte[0].maxInFlight()).isEqualTo(50);

			fonte[0].deliver(FakeMessageSource.message("agregado-1"));
			aguarda("ack da mensagem", () -> fonte[0].acked().size() == 1);
			assertThat(context.getBean(Tratador.Registro.class).envelopes()).hasSize(1);
			assertThat(fonte[0].closed()).isFalse();
		});
		assertThat(fonte[0].closed()).isTrue();
	}

	@Test
	void aplicaOsPadroesDeInboxConsumer() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
				.withUserConfiguration(BancoH2.class, FonteSimulada.class, Tratador.class)
				.withPropertyValues("inbox.consumer.queue=pagamentos")
				.run(context -> {
					InboxProperties props = context.getBean(InboxProperties.class);
					assertThat(props.queue()).isEqualTo("pagamentos");
					assertThat(props.maxAttempts()).isEqualTo(5);
					assertThat(props.backoff().initial()).isEqualTo(java.time.Duration.ofMillis(200));
					assertThat(props.backoff().multiplier()).isEqualTo(2.0);
					assertThat(props.backoff().max()).isEqualTo(java.time.Duration.ofSeconds(30));
					assertThat(props.maxInFlight()).isEqualTo(50);
					assertThat(props.workers()).isEqualTo(8);
					assertThat(props.confirmTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
					assertThat(props.toConfig().deadLetterQueue()).isEqualTo("pagamentos.dlq");
				});
	}

	@Test
	void leTodasAsChavesDeInboxConsumer() {
		contexto.withPropertyValues("inbox.consumer.dead-letter-queue=fila.morta", "inbox.consumer.max-attempts=3",
				"inbox.consumer.backoff.multiplier=1.5", "inbox.consumer.max-in-flight=7", "inbox.consumer.workers=2",
				"inbox.consumer.confirm-timeout=2s").run(context -> {
					FakeMessageSource fonte = context.getBean(FakeMessageSource.class);
					assertThat(fonte.deadLetterQueue()).isEqualTo("fila.morta");
					assertThat(fonte.maxInFlight()).isEqualTo(7);
					InboxProperties props = context.getBean(InboxProperties.class);
					assertThat(props.maxAttempts()).isEqualTo(3);
					assertThat(props.backoff().multiplier()).isEqualTo(1.5);
					assertThat(props.workers()).isEqualTo(2);
					assertThat(props.confirmTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
				});
	}

	@Test
	void configuracaoInvalidaFalhaASubida() {
		contexto.withPropertyValues("inbox.consumer.max-attempts=0").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context).getFailure().rootCause().hasMessageContaining("inbox.consumer.max-attempts");
		});
	}

	/** TC-36: sem o tratador ou sem a fila, nenhum bean do consumidor e o contexto sobe. */
	@Test
	void semTratadorOuSemFilaNaoCriaConsumidor() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
				.withUserConfiguration(BancoH2.class, FonteSimulada.class)
				.withPropertyValues("inbox.consumer.queue=fila.teste")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(InboxConsumer.class);
					assertThat(context).doesNotHaveBean(InboxDialect.class);
					assertThat(context.getBean(FakeMessageSource.class).started()).isFalse();
				});

		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
				.withUserConfiguration(BancoH2.class, FonteSimulada.class, Tratador.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(InboxConsumer.class);
					assertThat(context).doesNotHaveBean(InboxProperties.class);
					assertThat(context.getBean(FakeMessageSource.class).started()).isFalse();
				});
	}

	/** TC-36: a aplicação só com a outbox sobe como antes, com as duas auto-configurações registradas. */
	@Test
	void aplicacaoSoComAOutboxSobeSemOsBeansDoConsumidor() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class, InboxAutoConfiguration.class))
				.withUserConfiguration(OutboxAutoConfigurationTest.InfraestruturaDoTeste.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(OutboxGravador.class);
					assertThat(context).hasSingleBean(OutboxDespachante.class);
					assertThat(context).doesNotHaveBean(InboxConsumer.class);
					assertThat(context).doesNotHaveBean(InboxDialect.class);
					assertThat(context).doesNotHaveBean(InboxProperties.class);
				});
	}

	/** TC-37: nenhum bean definido pela aplicação é substituído. */
	@Test
	void naoSubstituiBeansDaAplicacao() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
				.withUserConfiguration(BancoH2.class, Tratador.class, BeansDaAplicacao.class)
				.withPropertyValues("inbox.consumer.queue=fila.teste")
				.run(context -> {
					assertThat(context).hasNotFailed();
					BeansDaAplicacao beans = context.getBean(BeansDaAplicacao.class);
					assertThat(context).hasSingleBean(InboxDialect.class);
					assertThat(context.getBean(InboxDialect.class)).isSameAs(beans.dialeto);
					assertThat(context).hasSingleBean(InboxTransactionExecutor.class);
					assertThat(context.getBean(InboxTransactionExecutor.class)).isSameAs(beans.transacoes);
					assertThat(context).hasSingleBean(BlockedKeys.class);
					assertThat(context.getBean(BlockedKeys.class)).isSameAs(beans.bloqueadas);
					assertThat(context).hasSingleBean(InboxMetrics.class);
					assertThat(context.getBean(InboxMetrics.class)).isSameAs(beans.metricas);
					assertThat(context).hasSingleBean(MessageSource.class);
					assertThat(context.getBean(MessageSource.class)).isSameAs(beans.fonte);
					assertThat(beans.fonte.started()).isTrue();
				});
	}

	/** TC-38: produto de banco diferente de H2 e SQL Server falha a subida nomeando o produto e os suportados. */
	@Test
	void produtoDeBancoNaoSuportadoFalhaASubida() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(InboxAutoConfiguration.class))
				.withUserConfiguration(BancoPostgres.class, FonteSimulada.class, Tratador.class)
				.withPropertyValues("inbox.consumer.queue=fila.teste")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context).getFailure().rootCause()
							.hasMessageContaining("PostgreSQL")
							.hasMessageContaining("Microsoft SQL Server")
							.hasMessageContaining("H2");
				});
	}

	@Test
	void detectaODialetoPeloProdutoDoBanco() {
		assertThat(InboxDialects.detect(dataSourceDoProduto("Microsoft SQL Server")))
				.isInstanceOf(SqlServerInboxDialect.class);
		assertThat(InboxDialects.detect(dataSourceDoProduto("H2"))).isInstanceOf(H2InboxDialect.class);
	}

	/** TC-39: o tratador executa na transação Spring; a falha desfaz efeito e inbox e a tentativa seguinte aplica. */
	@Test
	void tratadorExecutaNaTransacaoSpringEDesfazNaFalha() {
		contexto.run(context -> {
			FakeMessageSource fonte = context.getBean(FakeMessageSource.class);
			Tratador.Registro registro = context.getBean(Tratador.Registro.class);
			registro.falhaNaPrimeiraTentativa = true;
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

			fonte.deliver(FakeMessageSource.message("agregado-1"));

			aguarda("ack depois da repetição", () -> fonte.acked().size() == 1);
			assertThat(registro.tentativas.get()).isEqualTo(2);
			assertThat(registro.transacaoAtiva).containsOnly(true);
			// o valor do efeito é único: a segunda tentativa só passa se a primeira foi desfeita
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM efeito", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox", Integer.class)).isEqualTo(1);
			assertThat(fonte.deadLettered()).isEmpty();
		});
	}

	private static DataSource dataSourceDoProduto(String produto) {
		DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
				InboxAutoConfigurationTest.class.getClassLoader(), new Class<?>[] { DatabaseMetaData.class },
				(proxy, method, args) -> method.getName().equals("getDatabaseProductName") ? produto : null);
		Connection connection = (Connection) Proxy.newProxyInstance(InboxAutoConfigurationTest.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "getMetaData":
							return metadata;
						case "close":
							return null;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						default:
							throw new SQLException("não simulado: " + method.getName());
					}
				});
		return (DataSource) Proxy.newProxyInstance(InboxAutoConfigurationTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						return connection;
					}
					throw new SQLException("não simulado: " + method.getName());
				});
	}

	@Configuration(proxyBeanMethods = false)
	static class BancoH2 {

		@Bean
		DataSource dataSource() throws SQLException {
			DriverManagerDataSource dataSource = new DriverManagerDataSource(
					"jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
			InboxSchemaInstaller.install(dataSource, new H2InboxDialect());
			new JdbcTemplate(dataSource).execute(
					"CREATE TABLE efeito (id BIGINT AUTO_INCREMENT PRIMARY KEY, valor VARCHAR(50) NOT NULL UNIQUE)");
			return dataSource;
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class BancoPostgres {

		@Bean
		DataSource dataSource() {
			return dataSourceDoProduto("PostgreSQL");
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FonteSimulada {

		@Bean
		FakeMessageSource fonte() {
			return new FakeMessageSource();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Tratador {

		@Bean
		Registro registro() {
			return new Registro();
		}

		@Bean
		InboxMessageHandler tratador(Registro registro, JdbcTemplate jdbc) {
			return (envelope, connection) -> {
				registro.envelopes.add(envelope);
				registro.transacaoAtiva.add(TransactionSynchronizationManager.isActualTransactionActive());
				int tentativa = registro.tentativas.incrementAndGet();
				jdbc.update("INSERT INTO efeito (valor) VALUES (?)", "efeito-unico");
				if (registro.falhaNaPrimeiraTentativa && tentativa == 1) {
					throw new IllegalStateException("falha na primeira tentativa");
				}
			};
		}

		static final class Registro {

			final List<Envelope> envelopes = new CopyOnWriteArrayList<>();
			final List<Boolean> transacaoAtiva = new CopyOnWriteArrayList<>();
			final java.util.concurrent.atomic.AtomicInteger tentativas = new java.util.concurrent.atomic.AtomicInteger();
			volatile boolean falhaNaPrimeiraTentativa;

			List<Envelope> envelopes() {
				return envelopes;
			}

		}

	}

	@Configuration(proxyBeanMethods = false)
	static class BeansDaAplicacao {

		final InboxDialect dialeto = new H2InboxDialect();
		final InboxTransactionExecutor transacoes;
		final BlockedKeys bloqueadas = new InMemoryBlockedKeys();
		final InboxMetrics metricas = new InboxMetrics(new SimpleMeterRegistry());
		final FakeMessageSource fonte = new FakeMessageSource();

		BeansDaAplicacao(DataSource dataSource) {
			this.transacoes = new JdbcInboxTransactionExecutor(dataSource);
		}

		@Bean
		InboxDialect dialetoDaAplicacao() {
			return dialeto;
		}

		@Bean
		InboxTransactionExecutor transacoesDaAplicacao() {
			return transacoes;
		}

		@Bean
		BlockedKeys bloqueadasDaAplicacao() {
			return bloqueadas;
		}

		@Bean
		InboxMetrics metricasDaAplicacao() {
			return metricas;
		}

		@Bean
		FakeMessageSource fonteDaAplicacao() {
			return fonte;
		}

	}

}
