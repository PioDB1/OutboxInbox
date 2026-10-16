# Plano: Endpoint de inclusão de movimento

## Stack e estrutura

A stack e a estrutura são as do `AGENTS.md`. O `spring-boot-starter-webmvc`, o Spring Data JPA, a conexão com o SQL Server local, a geração de esquema pelo Hibernate e a `MovimentoEntity` já existem. A unidade cria cinco tipos Java no subpacote da entidade, `src/main/java/br/com/db1/demooutboxinbox/movimento/`:

- `MovimentoRepository.java`: interface `JpaRepository<MovimentoEntity, Long>`.
- `MovimentoRequest.java`: `record` do corpo da requisição, com `Instant dataHora`, `Character tipo`, `String descricao` e `BigDecimal valor`.
- `MovimentoResponse.java`: `record` do corpo da resposta `201`, com `Long id`, `Instant dataHora`, `Character tipo`, `String descricao` e `BigDecimal valor`, e uma fábrica estática a partir da `MovimentoEntity`.
- `MovimentoService.java`: `@Service` com o método `@Transactional` de inclusão.
- `MovimentoController.java`: `@RestController` com `POST /movimentos`.

O `pom.xml` recebe a dependência `org.springdoc:springdoc-openapi-starter-webmvc-ui` 3.1.0, que publica o Swagger UI do endpoint. Não mudam: `MovimentoEntity`, `DemooutboxinboxApplication`, `application.properties` e o teste `contextLoads`. O `@SpringBootApplication` encontra as novas classes e o repositório pela varredura do subpacote. As classes usam tabulação e Lombok (`@RequiredArgsConstructor` para a injeção por construtor), como o restante do código.

## Decisões técnicas

Uma linha por veredito. Contexto, alternativas e evidências estão em [`research.md`](./research.md).

- **Transação:** `@Transactional` no método público `MovimentoService.incluir(MovimentoRequest, boolean forcarFalha)`, chamado pelo controller por meio do proxy; commit e rollback feitos pelo `JpaTransactionManager` auto-configurado.
- **Gravação:** o serviço monta a `MovimentoEntity` a partir do `MovimentoRequest` e grava com `repository.saveAndFlush`, que executa o `INSERT` e preenche o `id` dentro da transação.
- **Falha forçada:** com `forcarFalha=true`, o serviço lança `IllegalStateException` logo depois do `saveAndFlush`, com mensagem que identifica a falha forçada e o `id` gerado; a exceção é `RuntimeException` e desfaz a transação.
- **Parâmetro de teste:** `@RequestParam(defaultValue = "false") boolean forcarFalha` no controller; valor não booleano gera `400` pelo Spring MVC.
- **Presença dos campos:** o controller verifica `dataHora`, `tipo`, `descricao` e `valor` contra `null` antes de chamar o serviço e lança `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`; sem `spring-boot-starter-validation`.
- **`tipo` no `record` como `Character`:** o JSON `null` chega como `null` à verificação de presença, e uma string com mais de um caractere falha na leitura do JSON com `400`.
- **Resposta `201`:** depois do `saveAndFlush`, o serviço chama `entityManager.refresh(entidade)` (`EntityManager` por `@PersistenceContext`) e devolve o `MovimentoResponse` montado da entidade relida, para que `valor` e `dataHora` reflitam o que o banco armazenou; o método do controller devolve o `MovimentoResponse` e é anotado com `@ResponseStatus(HttpStatus.CREATED)`, que o springdoc lê para documentar o `201` no Swagger UI; com `ResponseEntity.status(...)`, o status ficaria só em tempo de execução e a especificação gerada mostraria `200`.
- **Formato JSON:** o Jackson 3 auto-configurado serializa `Instant` como `2026-10-16T13:45:00Z`, `Character` como texto e `BigDecimal` com a escala lida do banco (`150.00`), sem configuração adicional.
- **Erros `500`:** sem `@ExceptionHandler` nem `@ControllerAdvice`; a falha forçada e as recusas do banco seguem para o tratamento padrão de erros do Spring Boot.
- **Swagger UI:** `springdoc-openapi-starter-webmvc-ui` 3.1.0, a linha do springdoc construída sobre o Spring Boot 4.1 (o `springdoc-openapi` 3.1.0 herda do `spring-boot-starter-parent` 4.1.0), com a configuração padrão: Swagger UI em `/swagger-ui.html` e especificação OpenAPI em `/v3/api-docs`, geradas em tempo de execução a partir do `MovimentoController`, sem anotações OpenAPI no código. Adotado por decisão humana registrada na D7 de `.agents/context/discovery-answers.md`, para enviar as requisições de teste pelo navegador.

## Modelo de dados

Sem `data-model.md`. A unidade não altera a tabela `movimento` nem a `MovimentoEntity`, descritas em [`../002-pagamentos-entidade-movimento/data-model.md`](../002-pagamentos-entidade-movimento/data-model.md).

## Contratos externos

O único contrato é o REST do `POST /movimentos`: corpo, parâmetro `forcarFalha`, respostas e corpo do `201` estão na seção "Fronteira do domínio" do [`spec.md`](./spec.md). Sem `contracts/`: o contrato cabe nas tabelas da spec, e a especificação OpenAPI do projeto é a gerada em tempo de execução pelo springdoc, sem arquivo versionado (decisão D7 de `.agents/context/discovery-answers.md`). A unidade não cria evento nem mensagem.

## Interface

Sem `ui/`. A unidade é só de backend.

## Estratégia de testes

A decisão D6 de `discovery-answers.md` e o `AGENTS.md` definem que não há testes unitários nem de integração além do `contextLoads`. A unidade não cria teste nem infraestrutura de teste. A verificação dos critérios de aceite usa o que já existe:

- **`contextLoads`:** `./mvnw test` a partir de `demooutboxinbox/`, com o `../compose.yaml` no ar. O contexto passa a carregar o repositório, o serviço e o controller; o teste passa sobre o código atual.
- **Chamadas HTTP:** com a aplicação iniciada por `./mvnw spring-boot:run`, chamadas com `curl` (`curl.exe` no Windows) a `http://localhost:8081/movimentos` cobrem o `201` com o corpo esperado, os `400` (campo ausente ou `null`, JSON inválido, `dataHora` fora do formato, `forcarFalha=talvez`) e os `500` (`tipo` `X`, `descricao` de 256 caracteres, `forcarFalha=true`). As mesmas requisições podem ser enviadas pelo Swagger UI, em `http://localhost:8081/swagger-ui.html`, com o corpo JSON e o parâmetro `forcarFalha` preenchidos no formulário do `POST /movimentos`.
- **Estado do banco:** a partir da raiz do repositório, `docker compose exec sqlserver /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "<senha do .env>"` confere a linha gravada no `201`, a contagem de linhas de `movimento` inalterada após cada `400` e `500`, e a sequência de `id` da história 3: inclusão com sucesso, inclusão com `forcarFalha=true` e nova inclusão com sucesso, cujo `id` é maior que o primeiro mais um.

## Impacto na documentação autoritativa

Há desvio deliberado. A skill de Pagamentos registra que o domínio não define regra de uso, endpoint nem evento para movimentações; o pedido desta unidade acrescenta o endpoint `POST /movimentos` com o parâmetro de teste `forcarFalha`. O documento afetado é:

- **[`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md)**, seção "Entidades e dados", subseção "Tabela `movimento` (SQL Server)": recebe o contrato do `POST /movimentos` (campos obrigatórios `dataHora` em ISO-8601 UTC, `tipo`, `descricao` e `valor`; parâmetro `forcarFalha`, que força uma falha depois do `INSERT` e antes do commit; respostas `201` com a movimentação registrada, `400` para campo ausente, nulo ou corpo ilegível e `500` para a falha forçada e as recusas do banco, sem gravar nada) e a gravação em transação local confirmada ao final e desfeita em qualquer falha anterior ao commit. Continuam valendo a ausência de evento e de vínculo com `pagamento` e cooperado. A descrição do frontmatter passa a citar o endpoint `POST /movimentos`.

A adoção do Swagger UI revê a decisão D7 e afeta dois documentos de projeto:

- **[`AGENTS.md`](../../../AGENTS.md)**: a stack cita o `springdoc-openapi-starter-webmvc-ui` 3.1.0, a restrição de documentação admite o Swagger UI gerado em tempo de execução como única documentação da API, e os comandos essenciais trazem o endereço do Swagger UI.
- **[`.agents/context/discovery-answers.md`](../../context/discovery-answers.md)**: a D7, a seção "Formas de documentação a manter" e o registro das decisões humanas trazem a adoção do Swagger UI.

Não mudam: [`references/technical-dependencies.md`](../../skills/pagamentos/references/technical-dependencies.md), porque a unidade não introduz precondição técnica de domínio; e a skill [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md), porque a inclusão de movimento não grava na outbox.
