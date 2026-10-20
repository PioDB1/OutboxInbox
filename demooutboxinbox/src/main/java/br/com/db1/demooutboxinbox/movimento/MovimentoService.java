package br.com.db1.demooutboxinbox.movimento;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
		MovimentoEntity entidade = prepararEntidade(request);

		repository.saveAndFlush(entidade);

		if (incluirEvento) {
			gravador.gravar(envelopeRegistrado(entidade));
		}

		if (forcarFalha) {
			throw new IllegalStateException(
					"Falha forçada por forcarFalha=true após o INSERT do movimento id=" + entidade.getId());
		}


		entityManager.refresh(entidade);
		return MovimentoResponse.from(entidade);
	}

	/**
	 * Grava duas movimentações e dois eventos na mesma transação.
	 * <p>
	 * Com {@code posicaoFalha} igual a 1 ou 2, lança {@link IllegalStateException} no ponto
	 * correspondente para demonstrar o rollback de ambos os movimentos e eventos.
	 */
	@Transactional
	public List<MovimentoResponse> incluirDuplo(List<MovimentoRequest> requests, int posicaoFalha) {
		MovimentoEntity e1 = prepararEntidade(requests.get(0));
		repository.saveAndFlush(e1);
		gravador.gravar(envelopeRegistrado(e1));

		if (posicaoFalha == 1) {
			throw new IllegalStateException("Falha forçada na posição 1 após o primeiro movimento");
		}

		MovimentoEntity e2 = prepararEntidade(requests.get(1));
		repository.saveAndFlush(e2);
		gravador.gravar(envelopeRegistrado(e2));

		if (posicaoFalha == 2) {
			throw new IllegalStateException("Falha forçada na posição 2 após o segundo movimento");
		}

		entityManager.refresh(e1);
		entityManager.refresh(e2);

		List<MovimentoResponse> responses = new ArrayList<>();
		responses.add(MovimentoResponse.from(e1));
		responses.add(MovimentoResponse.from(e2));
		return responses;
	}

	private static MovimentoEntity prepararEntidade(MovimentoRequest request) {
		MovimentoEntity entidade = new MovimentoEntity();
		entidade.setDataHora(request.dataHora());
		entidade.setTipo(request.tipo());
		entidade.setDescricao(request.descricao());
		entidade.setValor(request.valor());
		return entidade;
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
