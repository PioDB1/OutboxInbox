# Plano: Mensageria Outbox — código comum da outbox e da inbox no pacote `shared`

Responde ao "como" da [`spec.md`](./spec.md). Contexto, alternativas e evidências em [`research.md`](./research.md); aqui ficam os veredictos.

## Estado atual do código

Descrito na seção "Estado atual do código" da [`spec.md`](./spec.md). Pontos que condicionam o plano: `Texts` já é público e usado de outro pacote (`relay`, `schema`); `RelayConfig` e `OutboxSchemaInstaller` guardam a leitura de propriedades e de script como métodos privados; `RelayRunner.publish` abre o MDC com quatro `MDC.putCloseable` em um `try` com recursos; `SqlServerOutboxDialect.isDuplicateKey` é um método privado estático sobre duas constantes; `PackageBoundaryTest` caminha por todo `src/main/java` e aceita `com.rabbitmq` só em `br/com/db1/poc/outbox/rabbit`; o código comum do Spring importa `Envelope` e `RabbitTopology` de `br.com.db1.poc.outbox`.

## Stack e estrutura

Maven multi-módulo conforme `AGENTS.md`. O `shared` é um conjunto de pacotes `br.com.db1.poc.shared.*` no módulo `core` (Java 8, `--release 8`, enforcer de bytecode 8, sem Spring), sem artefato novo. A estrutura de destino:

| Pacote | Classe | Origem |
|---|---|---|
| `shared.envelope` | `Envelope` | movida de `outbox.envelope` |
| `shared.text` | `Texts` | movida de `outbox.internal` |
| `shared.config` | `PropertyReader` | extraída dos métodos `text`, `integer`, `decimal` e `duration` de `RelayConfig` |
| `shared.logging` | `MessageMdc` | extraída do `try` com recursos de `RelayRunner.publish` |
| `shared.rabbit` | `RabbitTopology` | movida de `outbox.rabbit` |
| `shared.schema` | `SqlScripts` | extraída de `OutboxSchemaInstaller` |
| `shared.dialect` | `SqlServerErrors` | extraída de `SqlServerOutboxDialect` |

Testes: `core/src/test/java/br/com/db1/poc/shared/` recebe `support` (`Await`, `HeapProbe`, `LogCapture`, `Images`, `ComposeImagesTest`, `TestInfrastructure`, `TestEnvelopes`), `envelope/EnvelopeTest` e `text/TextsTest`, e as classes de teste novas. `outbox.support` mantém `SqlServerIT`, `BrokerIT`, `OutboxDb`, `RelayIT` e `ScriptedPublisher`.

## Decisões técnicas

Contexto, alternativas e evidência de cada linha em [`research.md`](./research.md).

- **Onde fica o `shared`**: pacotes do próprio `core`, sob `br.com.db1.poc.shared`, sem módulo nem artefato novo.
- **O que entra**: só o que a outbox e a inbox usam com o mesmo comportamento; `BackoffPolicy` e as métricas ficam na outbox.
- **API de `PropertyReader`**: instância com o prefixo das chaves (`new PropertyReader(Properties, String prefix)`) e os métodos `text(key, default)`, `integer(key, default)`, `decimal(key, default)` e `duration(key, default)`, com as regras e mensagens atuais de `RelayConfig`.
- **API de `MessageMdc`**: `MessageMdc.open(String messageId, String correlationId, String aggregateKey, String traceId)` devolve um `AutoCloseable` que não lança exceção verificada; valor nulo não é colocado no MDC; `close` remove só as chaves colocadas.
- **API de `SqlScripts`**: `SqlScripts.readBatches(Class<?> anchor, String resource)` devolve `List<String>`; o recurso é resolvido relativo ao pacote de `anchor`, como `OutboxSchemaInstaller` faz hoje.
- **API de `SqlServerErrors`**: `SqlServerErrors.isDuplicateKey(SQLException)`.
- **Movimentação**: movimentação por refatoração do IDE ou `git mv` com ajuste de `package` e imports, sem alterar o conteúdo das classes movidas além do `package`.
- **Fronteira verificada**: `PackageBoundaryTest` com `com.rabbitmq` permitido em `shared/rabbit`, `outbox/rabbit` e `inbox/rabbit`, e com as regras de direção `shared` ↛ `outbox` e `inbox`, `outbox` ↛ `inbox`, `inbox` ↛ `outbox`.
- **Versão**: 0.4.0-SNAPSHOT, a mesma da unidade 003; a mudança de pacotes é a quebra de API dessa versão.

## Modelo de dados

Dispensado: a unidade não toca persistência; o texto das consultas SQL, o esquema e o script `schema.sql` permanecem idênticos.

## Contratos externos

Dispensado: o contrato da mensagem publicada e os nomes das classes públicas permanecem; só os pacotes de `Envelope` e `RabbitTopology` mudam, e o ajuste dos contratos descritivos entra em "Impacto na documentação autoritativa".

## Interface

Dispensada: o módulo não tem telas nem fluxos de interface.

## Estratégia de testes

- **Unitário (`*Test`, surefire, sem Docker)** — os testes existentes mantêm as asserções e mudam só imports e pacotes; novos: `PropertyReaderTest` (padrões, inteiro e decimal inválidos, durações `ms`, `s`, `m`, `h`, `d` e ISO-8601, mensagens com o prefixo), `MessageMdcTest` (colocação, valor nulo, limpeza, uso em `try` com recursos) com `LogCapture`, `SqlScriptsTest` (separador `GO` em maiúscula e minúscula, lotes em branco descartados, recurso ausente), `SqlServerErrorsTest` (2627, 2601 e outro código); `PackageBoundaryTest` ampliado com as regras de direção e de RabbitMQ.
- **Integração (`*IT`, failsafe, Testcontainers)** — mantêm as asserções, com os limites `-Xmx256m -XX:MaxDirectMemorySize=64m`; exigem Docker em execução e são ignorados sem ele.
- **Infraestrutura de teste já existente**: surefire e failsafe, JUnit Jupiter, Testcontainers 2.0.5, `BytecodeLevelTest`. Nada a introduzir.
- **Matriz**: o ciclo `verify` do `core` roda nos JDKs 8, 11, 17, 21 e 25 pelo procedimento `-Djvm` do README.

## Impacto na documentação autoritativa

Desvio deliberado: a decisão do requisitante é que o código comum à outbox e à inbox fica no pacote `shared`, e os textos abaixo citam `br.com.db1.poc.outbox.envelope.Envelope` e `br.com.db1.poc.outbox.rabbit.RabbitTopology` ou descrevem outbox e inbox como pacotes sem código comum. Cada item vira tarefa de atualização em `tasks.md`:

- **`.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/api-modulo.md`** — os pacotes de `Envelope` (`shared.envelope`) e de `RabbitTopology` (`shared.rabbit`); o pacote base da API passa a citar `br.com.db1.poc.outbox` e `br.com.db1.poc.shared`.
- **`.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md`** — a referência à topologia (`shared.rabbit.RabbitTopology`).
- **`AGENTS.md`** — a convenção arquitetural ganha a descrição do pacote `shared` e da direção das dependências.
- **`README.md` do componente** (documentação do módulo, não autoritativa) — imports e tabela de pacotes.
- **`mensageria-outbox-spring-boot3/README.md`** e **`mensageria-outbox-spring-boot4/README.md`** — imports de `Envelope` nos exemplos de uso.
- **`../mensageria-outbox-spring-boot-shared/.agents/skills/mensageria-outbox-spring-boot-shared/SKILL.md`** — os pacotes de `Envelope` e `RabbitTopology` citados no contrato da integração.
- **`demooutboxinbox`** — nenhuma alteração nesta unidade; a P2 de `../../../../demooutboxinbox/.agents/context/pendencias.md` cobre os imports do demo e das suas skills e mapas.
- **`.agents/context/pendencias.md`** — a P8 sai da lista ao entregar a unidade.
