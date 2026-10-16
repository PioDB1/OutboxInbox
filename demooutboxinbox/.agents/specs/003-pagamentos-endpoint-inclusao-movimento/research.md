# Pesquisa: Endpoint de inclusão de movimento

As versões citadas são as resolvidas pelo `spring-boot-starter-parent` 4.1.1 e empacotadas em `target/demooutboxinbox-0.0.1-SNAPSHOT.jar`: Spring Framework 7.0.9 (`spring-web`, `spring-webmvc`, `spring-tx`), Spring Data JPA 4.1.1, Hibernate 7.4.5, Jackson 3.1.5 (`tools.jackson.core:jackson-databind`) e `mssql-jdbc` 13.4.0. As fontes lidas são os `-sources.jar` dessas versões no repositório Maven local.

## Demarcação da transação

**Contexto.** A inclusão e a falha forçada precisam acontecer na mesma transação, de modo que a falha depois do `INSERT` desfaça a linha.

**Alternativas:**

- **`@Transactional` no método do serviço** — a transação do `JpaTransactionManager` auto-configurado envolve o `saveAndFlush` e a falha forçada; o Spring faz o commit ao retornar e o rollback ao propagar a exceção.
- **Demarcação explícita no código** (`PlatformTransactionManager`, `TransactionTemplate` ou `EntityTransaction`) — fora do escopo da unidade, que usa a transação gerenciada pelo Spring.

**Decisão:** `@Transactional` no método público do serviço, chamado pelo controller por meio do proxy do bean.

**Evidência:** a skill técnica [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md), seção "Gravação na transação JPA", define o método de serviço `@Transactional` com commit e rollback feitos pelo Spring, e a spec desta unidade fixa a transação declarativa. `DefaultTransactionAttribute.rollbackOn` (`spring-tx` 7.0.9, linha 180) devolve `ex instanceof RuntimeException || ex instanceof Error`: uma `RuntimeException` lançada no método desfaz a transação sem configuração de `rollbackFor`. O `SimpleJpaRepository` (Spring Data JPA 4.1.1) é `@Transactional(readOnly = true)` na classe e `saveAndFlush` é `@Transactional` com propagação padrão (`REQUIRED`), portanto participa da transação do serviço.

**Consequências:** a anotação só vale quando o método é chamado de fora do bean; o controller chama o serviço injetado, e o serviço não chama o próprio método.

## Momento do `INSERT` e falha forçada

**Contexto.** A falha forçada precisa ocorrer depois de o `INSERT` chegar ao banco e antes do commit.

**Decisão:** o serviço grava com `repository.saveAndFlush(entidade)` e, com `forcarFalha=true`, lança em seguida `IllegalStateException` com uma mensagem que identifica a falha forçada e o `id` gerado.

**Evidência:** `SimpleJpaRepository.save` (linhas 659 a 669) chama `entityManager.persist` para entidade nova, e `saveAndFlush` (linhas 673 a 679) chama `flush()` em seguida. Com `GenerationType.IDENTITY` na `MovimentoEntity`, o Hibernate executa o `INSERT` no `persist` para obter o `id`, e o `flush` garante a execução mesmo sem depender dessa característica. O `id` preenchido na entidade antes da exceção é o valor de identidade consumido no SQL Server. `IllegalStateException` é `RuntimeException`, portanto dispara o rollback (seção anterior).

**Consequências:** a linha existe na transação aberta no instante da falha e desaparece com o rollback; o valor de identidade não é devolvido pelo SQL Server, o que os critérios de aceite usam como prova.

## Validação de presença dos campos

**Contexto.** `dataHora`, `tipo`, `descricao` e `valor` ausentes ou nulos geram `400` sem tocar no banco.

**Alternativas:**

- **Bean Validation** (`spring-boot-starter-validation`, `@NotNull` e `@Valid`) — exige uma dependência que o `pom.xml` não declara.
- **Verificação no controller** — compara cada campo com `null` e lança `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` antes de chamar o serviço.

**Decisão:** verificação no controller, com `ResponseStatusException` de status `400`.

**Evidência:** o `pom.xml` não declara `spring-boot-starter-validation`, e a skill de Pagamentos restringe a validação à presença dos campos (regra 2), o que dispensa um framework de validação. `DefaultHandlerExceptionResolver` (`spring-webmvc` 7.0.9, linha 185) trata toda exceção que implementa `ErrorResponse`, como `ResponseStatusException`, com o status que ela carrega.

**Consequências:** nenhuma dependência nova; a mensagem de erro segue o tratamento padrão do Spring Boot.

## Leitura do JSON e respostas `400`

**Contexto.** Corpo ilegível, `dataHora` fora do formato ISO-8601, `tipo` com mais de um caractere e `forcarFalha` não booleano produzem `400`.

**Decisão:** o corpo é desserializado em um `record` `MovimentoRequest(Instant dataHora, Character tipo, String descricao, BigDecimal valor)`, e `forcarFalha` é `@RequestParam(defaultValue = "false") boolean forcarFalha`, sem tratamento de exceção próprio.

**Evidência:**

- `DefaultHandlerExceptionResolver` (linhas 233 e 236) responde `400` para `TypeMismatchException`, superclasse da `MethodArgumentTypeMismatchException` lançada na conversão de `forcarFalha=talvez`, e para `HttpMessageNotReadableException`, lançada pelo conversor JSON quando o Jackson falha.
- `NumberDeserializers.CharacterDeserializer` (Jackson 3.1.5, linhas 456 a 535): a instância de `Character` devolve `null` para JSON `null`, aceita string de um caractere e, para string mais longa, chama `handleWeirdStringValue`, que lança exceção de formato. Com `Character` no `record`, `tipo` nulo chega como `null` à verificação de presença; com o primitivo `char`, o JSON `null` viraria `'\0'` e escaparia da verificação.
- `BigDecimalDeserializer` (linhas 1062 em diante) lê números com `p.getDecimalValue()`, que preserva os dígitos enviados (`150.00` chega com escala 2).
- `Instant` é desserializado pelo `InstantDeserializer` do próprio `jackson-databind` 3.x; uma string que não é instante ISO-8601 falha na leitura e resulta em `HttpMessageNotReadableException`.

**Consequências:** os `400` de formato vêm do Spring MVC; o controller só trata a presença dos campos.

## Corpo da resposta `201`

**Contexto.** A resposta traz a movimentação registrada, com `dataHora` em ISO-8601 UTC, `tipo` como texto e `valor` com duas casas, refletindo o que o banco armazenou.

**Decisão:** depois do `saveAndFlush`, o serviço executa `entityManager.refresh(entidade)` e monta um `record` `MovimentoResponse(Long id, Instant dataHora, Character tipo, String descricao, BigDecimal valor)` a partir da entidade relida. O `EntityManager` entra no serviço por `@PersistenceContext`.

**Evidência:**

- `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` tem padrão `false` no Jackson 3.1.5 (`DateTimeFeature.java`, linha 174), e a auto-configuração do Spring Boot 4.1.1 (`JacksonAutoConfiguration`, linhas 515 a 519) só mexe nesse recurso com `spring.jackson.use-jackson2-defaults`, ausente no `application.properties`. Sem formatador configurado, o `InstantSerializer` usa `value.toString()` (`InstantSerializer`, linha 37; `InstantSerializerBase`, linha 152), que produz `2026-10-16T13:45:00Z`.
- `JDKCoreSerializers` (linhas 34 e 35) serializa `Character` e `char` como texto, portanto `tipo` sai como `"E"` ou `"S"`.
- O `refresh` relê a linha na mesma transação: `valor` volta como `decimal(18,2)` (escala 2, já arredondado pelo SQL Server quando o valor enviado tem mais casas, com `NUMERIC_ROUNDABORT` desligado por padrão) e `dataHora` volta na precisão do `datetime2`. Assim a resposta reflete o valor registrado sem reproduzir na aplicação a regra de arredondamento do banco.
- `EntityManagerBeanDefinitionRegistrarPostProcessor`, que permitia injetar `EntityManager` por construtor, está `@Deprecated(since = "4.0")` no Spring Data JPA 4.1.1. `@PersistenceContext` é a injeção do JPA tratada pelo Spring ORM e entrega o `EntityManager` compartilhado, ligado à transação corrente.

**Consequências:** um `SELECT` a mais por inclusão bem-sucedida. Com `forcarFalha=true`, a exceção é lançada antes do `refresh`.

## Respostas `500`

**Contexto.** A falha forçada e a recusa do banco (`tipo` fora de `E`/`S`, `descricao` acima de 255 caracteres, `valor` acima de `DECIMAL(18,2)`) respondem `500` sem gravar nada.

**Decisão:** nenhum `@ExceptionHandler` nem `@ControllerAdvice`; as exceções seguem para o tratamento padrão de erros do Spring Boot.

**Evidência:** `IllegalStateException` e a `DataAccessException` (como `DataIntegrityViolationException`) em que o proxy do repositório, anotado com `@Repository`, traduz o erro do SQL Server não implementam `ErrorResponse` nem são `TypeMismatchException` ou `HttpMessageNotReadableException`, portanto o `DefaultHandlerExceptionResolver` não as trata, e o Spring Boot responde `500` pelo seu controlador de erro. As duas são `RuntimeException` e desfazem a transação. O teste `contextLoads` (`./mvnw test`, executado com o SQL Server local no ar) passa sobre o código atual, com o contexto JPA e a tabela `movimento` em uso.

**Consequências:** o corpo de erro é o padrão do Spring Boot; o log da aplicação registra a pilha da exceção.
