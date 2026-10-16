package br.com.db1.demooutboxinbox.movimento;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/movimentos")
@RequiredArgsConstructor
public class MovimentoController {

	private final MovimentoService service;

	/**
	 * O {@code @ResponseStatus} define o {@code 201} da resposta e o status documentado no Swagger UI;
	 * o status de um {@code ResponseEntity} só existe em tempo de execução, e o springdoc documentaria {@code 200}.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MovimentoResponse incluir(@RequestBody MovimentoRequest request,
			@RequestParam(defaultValue = "false") boolean forcarFalha) {
		if (request.dataHora() == null || request.tipo() == null || request.descricao() == null
				|| request.valor() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"dataHora, tipo, descricao e valor são obrigatórios");
		}
		return service.incluir(request, forcarFalha);
	}

}
