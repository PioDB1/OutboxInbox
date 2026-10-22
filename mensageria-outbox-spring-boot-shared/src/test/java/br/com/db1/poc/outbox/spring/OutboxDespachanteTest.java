package br.com.db1.poc.outbox.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.shared.envelope.Envelope;

/**
 * Ordem por {@code aggregateKey} do {@link OutboxDespachante}, com a outbox, o banco e o broker simulados em memória:
 * o payload de cada mensagem é o próprio {@code seq}, e o publicador registra a ordem de publicação.
 */
class OutboxDespachanteTest {

	private static final OutboxRelayProperties PROPS = new OutboxRelayProperties("mensageria.outbox",
			Duration.ofSeconds(1), new OutboxRelayProperties.Backoff(Duration.ofMillis(1), 2.0, Duration.ofMillis(20)),
			100);

	private final OutboxEmMemoria outbox = new OutboxEmMemoria();

	private final PublicadorEmMemoria publicador = new PublicadorEmMemoria();

	private final OutboxDespachante despachante = new OutboxDespachante(publicador, outbox.dialeto(),
			dataSourceSemBanco(), PROPS);

	@AfterEach
	void encerrar() {
		despachante.destroy();
	}

	@Test
	void publicaEmOrdemDeSeqMesmoRecebendoForaDeOrdem() throws Exception {
		Envelope primeira = outbox.gravar(1, "A");
		Envelope segunda = outbox.gravar(2, "A");

		CompletableFuture<Boolean> envioDaSegunda = despachante.despachar(2, segunda);
		Thread.sleep(50);
		CompletableFuture<Boolean> envioDaPrimeira = despachante.despachar(1, primeira);

		assertThat(envioDaPrimeira.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(envioDaSegunda.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(publicador.publicadas()).containsExactly(1L, 2L);
	}

	@Test
	void falhaRetemSomenteAsPosterioresDaMesmaChave() throws Exception {
		Envelope a1 = outbox.gravar(1, "A");
		Envelope a2 = outbox.gravar(2, "A");
		Envelope b3 = outbox.gravar(3, "B");
		publicador.falhar(1, 3);

		List<CompletableFuture<Boolean>> envios = List.of(despachante.despachar(1, a1), despachante.despachar(2, a2),
				despachante.despachar(3, b3));
		for (CompletableFuture<Boolean> envio : envios) {
			assertThat(envio.get(2, TimeUnit.SECONDS)).isTrue();
		}

		List<Long> publicadas = publicador.publicadas();
		assertThat(publicadas).containsExactlyInAnyOrder(1L, 2L, 3L);
		assertThat(publicadas.indexOf(1L)).isLessThan(publicadas.indexOf(2L));
		assertThat(publicadas.indexOf(3L)).isLessThan(publicadas.indexOf(1L));
		assertThat(publicador.tentativas(1)).isEqualTo(4);
	}

	@Test
	void linhaAnteriorPendenteQueODespachanteNaoRecebeuRetemAChave() throws Exception {
		outbox.gravar(1, "A");
		Envelope segunda = outbox.gravar(2, "A");

		CompletableFuture<Boolean> envio = despachante.despachar(2, segunda);
		Thread.sleep(150);
		assertThat(envio).isNotDone();
		assertThat(publicador.publicadas()).isEmpty();

		outbox.marcarEnviada(1);
		assertThat(envio.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(publicador.publicadas()).containsExactly(2L);
	}

	@Test
	void linhaJaMarcadaComoEnviadaNaoEPublicada() throws Exception {
		Envelope envelope = outbox.gravar(1, "A");
		outbox.marcarEnviada(1);

		assertThat(despachante.despachar(1, envelope).get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(publicador.publicadas()).isEmpty();
	}

	@Test
	void falhaNaMarcacaoConcluiComFalseERetemAsPosterioresDaChave() throws Exception {
		Envelope a1 = outbox.gravar(1, "A");
		Envelope a2 = outbox.gravar(2, "A");
		outbox.falharMarcacao(1);

		CompletableFuture<Boolean> envio1 = despachante.despachar(1, a1);
		CompletableFuture<Boolean> envio2 = despachante.despachar(2, a2);

		assertThat(envio1.get(2, TimeUnit.SECONDS)).isFalse();
		Thread.sleep(150);
		assertThat(envio2).isNotDone();
		assertThat(publicador.publicadas()).containsExactly(1L);
	}

	@Test
	void linhaEntregueDuasVezesEPublicadaUmaVez() throws Exception {
		Envelope envelope = outbox.gravar(1, "A");

		CompletableFuture<Boolean> primeiro = despachante.despachar(1, envelope);
		CompletableFuture<Boolean> segundo = despachante.despachar(1, envelope);

		assertThat(primeiro.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(segundo.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(publicador.publicadas()).containsExactly(1L);
	}

	private static DataSource dataSourceSemBanco() {
		Connection conexao = (Connection) Proxy.newProxyInstance(OutboxDespachanteTest.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, metodo, args) -> null);
		return (DataSource) Proxy.newProxyInstance(OutboxDespachanteTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, metodo, args) -> {
					if (metodo.getName().equals("getConnection")) {
						return conexao;
					}
					throw new UnsupportedOperationException(metodo.getName());
				});
	}

	/** Linhas da outbox em memória, com as consultas do dialeto usadas pelo despachante. */
	private static final class OutboxEmMemoria {

		private record Linha(String aggregateKey, boolean pendente) {
		}

		private final Map<Long, Linha> linhas = new ConcurrentHashMap<>();

		private final List<Long> marcacoesComFalha = Collections.synchronizedList(new ArrayList<>());

		Envelope gravar(long seq, String aggregateKey) {
			linhas.put(seq, new Linha(aggregateKey, true));
			return Envelope.novo(UUID.randomUUID(), "chave-" + seq, aggregateKey, "Teste", (short) 1, Instant.now(),
					Long.toString(seq).getBytes(StandardCharsets.UTF_8), "trace-" + seq);
		}

		void marcarEnviada(long seq) {
			linhas.computeIfPresent(seq, (s, linha) -> new Linha(linha.aggregateKey(), false));
		}

		void falharMarcacao(long seq) {
			marcacoesComFalha.add(seq);
		}

		OutboxDialect dialeto() {
			return (OutboxDialect) Proxy.newProxyInstance(OutboxDespachanteTest.class.getClassLoader(),
					new Class<?>[] { OutboxDialect.class }, (proxy, metodo, args) -> {
						switch (metodo.getName()) {
						case "hasPendingBetween": {
							String chave = (String) args[1];
							long depois = (Long) args[2];
							long antes = (Long) args[3];
							return linhas.entrySet().stream().anyMatch(e -> e.getKey() > depois && e.getKey() < antes
									&& e.getValue().aggregateKey().equals(chave) && e.getValue().pendente());
						}
						case "markSent": {
							@SuppressWarnings("unchecked")
							List<Long> seqs = (List<Long>) args[1];
							for (long seq : seqs) {
								if (marcacoesComFalha.contains(seq)) {
									throw new SQLException("falha simulada na marcação da linha " + seq);
								}
								marcarEnviada(seq);
							}
							return null;
						}
						default:
							throw new UnsupportedOperationException(metodo.getName());
						}
					});
		}

	}

	/** Publicador que registra a ordem das publicações e falha um número configurado de vezes por linha. */
	private static final class PublicadorEmMemoria implements MessagePublisher {

		private final List<Long> publicadas = Collections.synchronizedList(new ArrayList<>());

		private final Map<Long, Integer> falhasRestantes = new ConcurrentHashMap<>();

		private final Map<Long, Integer> tentativas = new ConcurrentHashMap<>();

		void falhar(long seq, int vezes) {
			falhasRestantes.put(seq, vezes);
		}

		List<Long> publicadas() {
			synchronized (publicadas) {
				return new ArrayList<>(publicadas);
			}
		}

		int tentativas(long seq) {
			return tentativas.getOrDefault(seq, 0);
		}

		@Override
		public void publish(PublishableMessage message) throws PublishException {
			long seq = Long.parseLong(new String(message.payload(), StandardCharsets.UTF_8));
			tentativas.merge(seq, 1, Integer::sum);
			if (falhasRestantes.getOrDefault(seq, 0) > 0) {
				falhasRestantes.merge(seq, -1, Integer::sum);
				throw new PublishException("falha simulada na publicação da linha " + seq);
			}
			publicadas.add(seq);
		}

		@Override
		public void close() {
		}

	}

}
