package br.com.db1.poc.outbox.spring;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.relay.BackoffPolicy;

/**
 * Publica as linhas da outbox de forma assíncrona, preservando a ordem por {@code aggregateKey}: as mensagens da
 * mesma chave são publicadas uma de cada vez, em ordem de {@code seq}, e cada uma só é publicada depois do confirm
 * positivo do broker e da marcação de {@code sent_at} da anterior. Uma mensagem em falha retém as posteriores da
 * mesma chave; as demais chaves seguem.
 * <p>
 * Antes de publicar a primeira mensagem conhecida de uma chave, o despachante consulta o banco com
 * {@link OutboxDialect#hasPendingBetween} ({@code READUNCOMMITTED}): uma linha pendente anterior da mesma chave que
 * ele ainda não recebeu (transação ainda não confirmada, ou linha de uma execução anterior que a recuperação ainda
 * não leu) retém a chave até chegar ao despachante ou deixar de existir. A mesma consulta descarta a linha que já
 * está marcada como enviada.
 * <p>
 * O agendador tem uma única thread, que guarda todo o estado das chaves: o {@code publish} do
 * {@code RabbitMessagePublisher} é sincronizado sobre um único canal, e threads adicionais só ficariam bloqueadas
 * nele. A publicação é repetida, com intervalo crescente e sem limite de tentativas, até o confirm.
 */
public class OutboxDespachante implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(OutboxDespachante.class);

	/** Limite de chaves com o último {@code seq} enviado em memória, usado como piso da consulta de anteriores. */
	private static final int CHAVES_LEMBRADAS = 10_000;

	private enum Resultado {
		MARCADA, NAO_MARCADA, JA_ENVIADA, RETIDA
	}

	/** Mensagens pendentes de um {@code aggregateKey}, em ordem de {@code seq}. */
	private static final class Chave {

		private final TreeMap<Long, Envelope> pendentes = new TreeMap<>();

		private int falhas;

		/** Próxima tentativa agendada e ainda não iniciada; {@code null} quando a chave não tem tentativa agendada. */
		private ScheduledFuture<?> tentativa;

		/** A chave aguarda uma linha anterior que o despachante ainda não recebeu. */
		private boolean aguardandoAnterior;

	}

	private final MessagePublisher publisher;

	private final OutboxDialect dialect;

	private final DataSource dataSource;

	private final BackoffPolicy backoff;

	private final ThreadPoolTaskScheduler agendador = new ThreadPoolTaskScheduler();

	private final ConcurrentMap<Long, CompletableFuture<Boolean>> resultados = new ConcurrentHashMap<>();

	// Estado abaixo: acessado somente pela thread do agendador.

	private final Map<String, Chave> chaves = new HashMap<>();

	private final Map<String, Long> ultimoEnviadoPorChave = new LinkedHashMap<>(16, 0.75f, true) {

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Long> maisAntiga) {
			return size() > CHAVES_LEMBRADAS;
		}

	};

	public OutboxDespachante(MessagePublisher publisher, OutboxDialect dialect, DataSource dataSource,
			OutboxRelayProperties props) {
		this.publisher = publisher;
		this.dialect = dialect;
		this.dataSource = dataSource;
		this.backoff = new BackoffPolicy(props.backoff().initial(), props.backoff().multiplier(),
				props.backoff().max(), 1);
		agendador.setPoolSize(1);
		agendador.setThreadNamePrefix("outbox-despacho-");
		agendador.initialize();
	}

	/**
	 * Entrega a linha gravada ao envio assíncrono e retorna em seguida; roda na thread que confirmou a transação e
	 * não publica no broker.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void aoGravar(OutboxGravada gravada) {
		despachar(gravada.seq(), gravada.envelope());
	}

	/**
	 * Agenda a publicação da linha. Uma linha já entregue e ainda não concluída devolve o mesmo futuro.
	 *
	 * @return futuro concluído com {@code true} quando a linha foi publicada e marcada como enviada, ou quando já
	 *         estava marcada; com {@code false} quando o broker confirmou e a marcação falhou; com exceção quando a
	 *         aplicação encerra antes do confirm. Nos dois últimos casos a linha permanece pendente no banco.
	 */
	public CompletableFuture<Boolean> despachar(long seq, Envelope envelope) {
		CompletableFuture<Boolean> novo = new CompletableFuture<>();
		CompletableFuture<Boolean> existente = resultados.putIfAbsent(seq, novo);
		if (existente != null) {
			return existente;
		}
		try {
			agendador.execute(() -> enfileirar(seq, envelope));
		} catch (TaskRejectedException e) {
			interromper(seq, e);
		}
		return novo;
	}

	private void enfileirar(long seq, Envelope envelope) {
		String chave = envelope.aggregateKey();
		Chave estado = chaves.computeIfAbsent(chave, k -> new Chave());
		estado.pendentes.put(seq, envelope);
		if (estado.tentativa == null) {
			agendarTentativa(chave, estado, Duration.ZERO);
		} else if (estado.aguardandoAnterior) {
			// a linha que retinha a chave pode ser esta: verifica de novo sem esperar o intervalo
			estado.tentativa.cancel(false);
			agendarTentativa(chave, estado, Duration.ZERO);
		}
	}

	private void agendarTentativa(String chave, Chave estado, Duration atraso) {
		try {
			estado.tentativa = agendador.schedule(() -> tentar(chave), Instant.now().plus(atraso));
		} catch (TaskRejectedException e) {
			estado.tentativa = null;
			estado.pendentes.keySet().forEach(seq -> interromper(seq, e));
			chaves.remove(chave);
		}
	}

	private void tentar(String chave) {
		Chave estado = chaves.get(chave);
		if (estado == null || estado.pendentes.isEmpty()) {
			return;
		}
		estado.tentativa = null;
		estado.aguardandoAnterior = false;
		Map.Entry<Long, Envelope> primeira = estado.pendentes.firstEntry();
		long seq = primeira.getKey();
		Envelope envelope = primeira.getValue();
		try (MDC.MDCCloseable messageId = MDC.putCloseable("messageId", envelope.messageId().toString());
				MDC.MDCCloseable correlationId = MDC.putCloseable("correlationId",
						envelope.correlationId().toString());
				MDC.MDCCloseable aggregateKey = MDC.putCloseable("aggregateKey", chave);
				MDC.MDCCloseable traceId = MDC.putCloseable("traceId", envelope.traceId())) {
			Resultado resultado;
			try {
				resultado = publicarEmOrdem(chave, seq, envelope);
			} catch (PublishException | SQLException | RuntimeException e) {
				estado.falhas++;
				Duration atraso = backoff.delayAfter(estado.falhas);
				log.warn("Falha na publicação da linha seq={} (tentativa {}): {}; nova tentativa em {}", seq,
						estado.falhas, e.getMessage(), atraso);
				agendarTentativa(chave, estado, atraso);
				return;
			}
			if (resultado == Resultado.RETIDA) {
				estado.falhas++;
				estado.aguardandoAnterior = true;
				Duration atraso = backoff.delayAfter(estado.falhas);
				log.info("Linha seq={} aguarda a publicação de uma linha anterior do mesmo aggregateKey; "
						+ "nova verificação em {}", seq, atraso);
				agendarTentativa(chave, estado, atraso);
				return;
			}
			estado.falhas = 0;
			estado.pendentes.remove(seq);
			concluir(seq, resultado != Resultado.NAO_MARCADA);
		}
		if (estado.pendentes.isEmpty()) {
			chaves.remove(chave);
		} else {
			agendarTentativa(chave, estado, Duration.ZERO);
		}
	}

	/**
	 * Publica a linha se ela ainda está pendente e nenhuma linha anterior da mesma chave está pendente. O piso da
	 * consulta de anteriores é o último {@code seq} da chave enviado nesta execução: as linhas abaixo dele já foram
	 * enviadas ou desfeitas. Uma linha confirmada cuja marcação falhou continua pendente no banco e retém as
	 * posteriores da chave até a recuperação da próxima inicialização.
	 */
	private Resultado publicarEmOrdem(String chave, long seq, Envelope envelope)
			throws SQLException, PublishException {
		long piso = ultimoEnviadoPorChave.getOrDefault(chave, 0L);
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(true);
			if (!dialect.hasPendingBetween(connection, chave, seq - 1, seq + 1)) {
				log.info("Linha seq={} já está marcada como enviada; publicação descartada", seq);
				ultimoEnviadoPorChave.merge(chave, seq, Math::max);
				return Resultado.JA_ENVIADA;
			}
			if (dialect.hasPendingBetween(connection, chave, piso, seq)) {
				return Resultado.RETIDA;
			}
		}
		publisher.publish(PublishableMessage.from(envelope));
		if (!marcarEnviada(seq)) {
			return Resultado.NAO_MARCADA;
		}
		ultimoEnviadoPorChave.merge(chave, seq, Math::max);
		return Resultado.MARCADA;
	}

	/**
	 * Marca {@code sent_at} em uma conexão própria, em autocommit e fora de qualquer transação JPA. Uma falha aqui
	 * não republica a mensagem: a linha continua pendente e é reenviada pela recuperação da próxima inicialização.
	 */
	private boolean marcarEnviada(long seq) {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(true);
			dialect.markSent(connection, List.of(seq));
			log.info("Mensagem da linha seq={} confirmada pelo broker e marcada como enviada", seq);
			return true;
		} catch (SQLException e) {
			log.error("Mensagem da linha seq={} confirmada pelo broker, mas a marcação de sent_at falhou; "
					+ "a linha e as posteriores do mesmo aggregateKey permanecem pendentes até a recuperação da "
					+ "próxima inicialização", seq, e);
			return false;
		}
	}

	private void concluir(long seq, boolean marcada) {
		CompletableFuture<Boolean> resultado = resultados.remove(seq);
		if (resultado != null) {
			resultado.complete(marcada);
		}
	}

	private void interromper(long seq, Exception causa) {
		CompletableFuture<Boolean> resultado = resultados.remove(seq);
		if (resultado != null) {
			log.warn("Envio da linha seq={} interrompido pelo encerramento da aplicação; a linha permanece pendente",
					seq);
			resultado.completeExceptionally(causa);
		}
	}

	@Override
	public void destroy() {
		agendador.shutdown();
		IllegalStateException encerramento = new IllegalStateException("Despachante da outbox encerrado");
		resultados.keySet().forEach(seq -> interromper(seq, encerramento));
	}

}
