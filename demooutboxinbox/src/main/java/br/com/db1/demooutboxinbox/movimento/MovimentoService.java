package br.com.db1.demooutboxinbox.movimento;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MovimentoService {

	private final MovimentoRepository repository;

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
	public MovimentoResponse incluir(MovimentoRequest request, boolean forcarFalha) {
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

		entityManager.refresh(entidade);
		return MovimentoResponse.from(entidade);
	}

}
