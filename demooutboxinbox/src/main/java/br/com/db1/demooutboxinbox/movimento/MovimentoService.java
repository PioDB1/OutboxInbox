package br.com.db1.demooutboxinbox.movimento;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;

import br.com.db1.poc.outbox.envelope.Envelope;
import br.com.db1.poc.outbox.spring.OutboxGravador;

@Service
@AllArgsConstructor
public class MovimentoService {

	private final MovimentoRepository repository;
	private final OutboxGravador gravador;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Grava a movimentação e devolve a linha relida do banco, com {@code valor} e {@code dataHora}
	 * como a coluna os armazenou.
	 * <p>
	 * Com {@code forcarFalha}, lança {@link IllegalStateException} depois de o {@code INSERT}
	 * chegar ao banco e antes do commit, e o Spring desfaz a transação.
	 */
	@Transactional
	public MovimentoResponse incluir(MovimentoRequest request, boolean forcarFalha, boolean incluirEvento) {
		MovimentoEntity entidade = new MovimentoEntity();
		entidade.setDataHora(request.dataHora());
		entidade.setTipo(request.tipo());
		entidade.setDescricao(request.descricao());
		entidade.setValor(request.valor());

		repository.saveAndFlush(entidade);

		if (forcarFalha) {
			throw new IllegalStateException(
					"Falha forçada por forcarFalha=true após o INSERT do movimento id=" + entidade.getId());
		}

		if (incluirEvento) {
			gravador.gravar(envelopeRegistrado(entidade));
		}

		entityManager.refresh(entidade);
		return MovimentoResponse.from(entidade);
	}

	/**
	 * Monta o envelope de {@code MovimentoRegistrado} v1: {@code aggregateKey} é o {@code id} do movimento, o payload
	 * traz só o {@code movimentoId} e o {@code tipo}, e {@code messageId} é reutilizado como {@code idempotencyKey}.
	 * A requisição não tem headers de rastreamento, então {@code correlationId} e {@code traceId} são gerados.
	 */
	private static Envelope envelopeRegistrado(MovimentoEntity entidade) {
		UUID messageId = UUID.randomUUID();
		String payload = String.format("{\"movimentoId\":%d,\"tipo\":\"%c\"}", entidade.getId(), entidade.getTipo());
		return new Envelope(messageId, UUID.randomUUID(), messageId.toString(), entidade.getId().toString(),
				MovimentoMensageriaConfiguration.TIPO_EVENTO, (short) 1, Instant.now(),
				payload.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
	}

}
