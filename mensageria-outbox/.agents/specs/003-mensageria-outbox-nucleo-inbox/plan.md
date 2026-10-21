# Plano: Mensageria Outbox — núcleo da inbox no `mensageria-outbox-core`

Responde ao "como" da [`spec.md`](./spec.md). Contexto, alternativas e evidências de cada decisão estão em [`research.md`](./research.md); aqui ficam os veredictos.

## Estado atual do código

Descrito na seção "Estado atual do código" da [`spec.md`](./spec.md). Do que já existe e é reaproveitado:

- `core` (depois da unidade 004): `shared.envelope.Envelope` (campos, validações e tamanhos máximos do contrato), `shared.rabbit.RabbitTopology` (argumentos da fila quorum), `shared.schema.SqlScripts` (leitura do script em lotes separados por `GO`), `shared.text.Texts`, `shared.config.PropertyReader` (leitura de propriedades `ms`, `s`, `m`, `h`, `d` ou ISO-8601), `shared.logging.MessageMdc`, `shared.dialect.SqlServerErrors` (violação de chave pelos códigos 2627 e 2601), `metrics.OutboxMetrics` (padrão de contadores Micrometer com nomes em constantes), `relay.RelayConfig` (padrão de configuração validada com `fromProperties`), `write.OutboxWriter` (recusa conexão em autocommit), `dialect.sqlserver.SqlServerOutboxDialect` (UUID como texto), `PackageBoundaryTest` com as regras de direção entre `shared`, `outbox` e `inbox`, `RelayMemoryIT` e a infraestrutura de teste `shared.support` (`TestInfrastructure`, `LogCapture`, `Await`, `HeapProbe`, `TestEnvelopes`).
- `mensageria-outbox-spring-boot-shared`: `OutboxAutoConfiguration` (`@ConditionalOnMissingBean` em todo bean, `@Import` da `OutboxRabbitConnectionFactoryConfiguration`), `OutboxRelayProperties` (record com `@DefaultValue`) e os testes com `ApplicationContextRunner`.
- Os dois adaptadores Spring Boot compilam o código comum com `build-helper-maven-plugin`, com piso Java 17, e dependem de `mensageria-outbox-core` na versão do componente.

## Stack e estrutura

Maven multi-módulo conforme `AGENTS.md`. O código do consumidor entra no módulo `core` (Java 8 como piso, sem Spring), no pacote base `br.com.db1.poc.inbox` (ao lado do `br.com.db1.poc.outbox`), e a integração Spring entra no código comum, no pacote `br.com.db1.poc.outbox.spring`, onde já vive a `OutboxRabbitConnectionFactoryConfiguration`, que é package-private e entra na `InboxAutoConfiguration` por `@Import`. O código do consumidor usa o que é comum pelo pacote `br.com.db1.poc.shared` (unidade 004) e não importa nada de `br.com.db1.poc.outbox`; a outbox não importa nada de `br.com.db1.poc.inbox`. A estrutura nova:

| Local | Conteúdo adicionado |
|---|---|
| `core/.../br/com/db1/poc/inbox` | `InboxConsumer`, `InboxConfig`, `InboxMessageHandler`, `InboxTransactionExecutor`, `JdbcInboxTransactionExecutor`, `EnvelopeReader`, `InvalidEnvelopeException`, `MessageSource`, `Delivery`, `BlockedKeys`, `InMemoryBlockedKeys`, `KeyedExecutor` (fila de execução por chave), `RetryBackoff` |
| `core/.../br/com/db1/poc/inbox/dialect` | `InboxDialect`; `sqlserver/SqlServerInboxDialect`; `h2/H2InboxDialect` |
| `core/.../br/com/db1/poc/inbox/schema` | `InboxSchemaInstaller` |
| `core/.../br/com/db1/poc/inbox/rabbit` | `RabbitMessageSource` |
| `core/.../br/com/db1/poc/shared/rabbit` | `RabbitTopology.declareQuorumQueue(Channel, String)` (sobrecarga para a DLQ, acrescentada à classe movida pela unidade 004) |
| `core/.../br/com/db1/poc/inbox/metrics` | `InboxMetrics` |
| `core/src/main/resources/br/com/db1/poc/inbox/schema/{sqlserver,h2}/schema.sql` | script idempotente por dialeto |
| `mensageria-outbox-spring-boot-shared/.../spring` | `InboxAutoConfiguration`, `InboxProperties`, `SpringInboxTransactionExecutor`, `InboxConsumerLifecycle`, `InboxDialects` (detecção do dialeto) |
| `mensageria-outbox-spring-boot3` e `-boot4` | linha `br.com.db1.poc.outbox.spring.InboxAutoConfiguration` no `AutoConfiguration.imports` |

## Parâmetros

Chaves `inbox.consumer.*` (`InboxConfig.fromProperties` no `core`, `InboxProperties` no Spring), com os padrões escolhidos por esta unidade (a skill `mensageria-inbox` não define valores; ver [`research.md`](./research.md), "Parâmetros e padrões"):

| Chave | Padrão | Significado |
|---|---|---|
| `inbox.consumer.queue` | sem padrão, obrigatória | fila principal (quorum, durável, declarada pela aplicação) |
| `inbox.consumer.dead-letter-queue` | `<queue>.dlq` | DLQ (quorum, durável, declarada pela aplicação) |
| `inbox.consumer.max-attempts` | `5` | tentativas de processamento por mensagem antes da DLQ (a primeira conta) |
| `inbox.consumer.backoff.initial` | `200ms` | intervalo depois da primeira falha |
| `inbox.consumer.backoff.multiplier` | `2.0` | fator de crescimento do intervalo (finito e maior ou igual a 1,0) |
| `inbox.consumer.backoff.max` | `30s` | teto do intervalo |
| `inbox.consumer.max-in-flight` | `50` | mensagens sem ack, igual ao `basicQos` |
| `inbox.consumer.workers` | `8` | threads de trabalho que executam as filas por chave |
| `inbox.consumer.confirm-timeout` | `5s` | espera do confirm da publicação na DLQ |

As durações usam o formato de `RelayConfig` (`ms`, `s`, `m`, `h`, `d`) no `core` e `Duration` no Spring, como na outbox.

## Decisões técnicas

Contexto, alternativas e evidência de cada linha em [`research.md`](./research.md).

- **Artefato e Java**: o consumidor fica no `mensageria-outbox-core`, com bytecode e API Java 8 e a regra `enforceBytecodeVersion` do módulo; nenhum artefato novo.
- **Envelope**: o consumidor lê para o `shared.envelope.Envelope`; `EnvelopeReader` converte `Delivery` em `Envelope` e lança `InvalidEnvelopeException` para header ausente ou fora do formato.
- **Porta de consumo (R14)**: `MessageSource` e `Delivery` sem tipo do RabbitMQ; `inbox.rabbit.RabbitMessageSource` é o único adaptador do consumidor; o `PackageBoundaryTest` (unidade 004) aceita `com.rabbitmq` em `inbox/rabbit` e impede a inbox de importar a outbox.
- **Transação (R4)**: `InboxTransactionExecutor` executa o trabalho em uma transação local e entrega a conexão ao trabalho; `JdbcInboxTransactionExecutor` (autocommit desligado, commit e rollback) para uso sem framework e `SpringInboxTransactionExecutor` (`TransactionTemplate` e `DataSourceUtils`) no Spring.
- **Ordem das operações na transação**: consulta do `messageId` na inbox, inserção na inbox e só então o tratador; a violação de chave primária na inserção significa duplicata.
- **Duplicata concorrente (R6)**: só a violação da chave primária lançada pela inserção na inbox, reconhecida por `InboxDialect.isDuplicateKey`, é duplicata; exceção do tratador é falha de processamento.
- **Serialização por chave (R8, R8a)**: `KeyedExecutor` com um mapa de chave para fila de tarefas e um `ThreadPoolExecutor` de `workers` threads; a chave é removida do mapa quando a fila esvazia; o limite de mensagens em voo vem do `basicQos`.
- **Retry (R9, R10a)**: na memória do consumidor, com a chave retendo a posse da vez durante a espera e a repetição agendada em um `ScheduledExecutorService`, que não ocupa thread de trabalho; `RetryBackoff` calcula o intervalo e valida a configuração.
- **DLQ (R10, R10b, R11)**: publicação no exchange padrão com a DLQ como routing key, `mandatory=true` e confirm, antes do ack da original; `BlockedKeys` (interface) com `InMemoryBlockedKeys` como única implementação (P7).
- **Chave sem `aggregateKey` legível**: serializada sob a chave reservada `<sem-aggregateKey>`.
- **Dialetos (R12)**: `InboxDialect` com `SqlServerInboxDialect` e `H2InboxDialect`, sem import de classe do driver; o H2 do `core` é dependência de teste na versão 2.2.224.
- **Esquema**: scripts idempotentes por dialeto com lotes separados por `GO`, executados por `InboxSchemaInstaller`; a auto-configuração não o chama, como na outbox.
- **Métricas (R15)**: `InboxMetrics` com `inbox.duplicates.discarded`, `inbox.dlq.messages` e `inbox.process.retries`.
- **Logs (R16)**: SLF4J com MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` em volta de cada tentativa, por `shared.logging.MessageMdc`, limpo em `finally`.
- **Configuração**: `InboxConfig.fromProperties` lê as chaves com `shared.config.PropertyReader`.
- **Esquema e violação de chave**: `InboxSchemaInstaller` lê o script com `shared.schema.SqlScripts` e `SqlServerInboxDialect` reconhece a violação com `shared.dialect.SqlServerErrors`.
- **Declaração das filas**: a aplicação declara a fila principal e a DLQ; ao iniciar, o consumidor confirma as duas com `queueDeclarePassive` e falha se alguma não existir.
- **Spring**: `InboxAutoConfiguration` separada da `OutboxAutoConfiguration`, com `@Import` da `OutboxRabbitConnectionFactoryConfiguration`; `@ConditionalOnMissingBean` em todo bean; o `InboxConsumer` só é criado com `inbox.consumer.queue` definida e um bean `InboxMessageHandler`; dialeto detectado pelo `DatabaseMetaData.getDatabaseProductName`.
- **Versão**: 0.4.0-SNAPSHOT em todos os artefatos do componente.

## Modelo de dados

Tabela `inbox` por dialeto (SQL Server e H2), com `message_id` chave primária e `consumed_at` obrigatório. Detalhes em [`data-model.md`](./data-model.md).

## Contratos externos

- API Java pública do consumidor e chaves de configuração em [`contracts/api-inbox.md`](./contracts/api-inbox.md).
- Mensagem consumida: o contrato já existente [`001/contracts/mensagem-publicada.md`](../001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md), sem alteração; a mensagem na DLQ acrescenta os headers `x-inbox-failure` e `x-inbox-attempts` descritos em `contracts/api-inbox.md`.

## Interface

Dispensada: o módulo não tem telas nem fluxos de interface.

## Estratégia de testes

- **Unitário (`*Test`, surefire, sem Docker)** — `EnvelopeReaderTest`, `InboxConfigTest` (incluindo `NaN`), `RetryBackoffTest`, `KeyedExecutorTest` (ordem por chave, paralelismo entre chaves, liberação da chave), `InboxProcessingTest` (dedup, duplicata por violação, falha e rollback, retry, DLQ, chave bloqueada, ack após commit) com `InMemoryInboxDialect` e uma `MessageSource` simulada, `InboxMetricsTest`, `InboxDialectH2Test` sobre H2 em memória, `InboxSchemaInstallerH2Test`, `InboxMdcTest` com `LogCapture`; o `PackageBoundaryTest` ampliado cobre o código novo (RabbitMQ só nos pacotes `rabbit` da outbox e da inbox, Spring proibido em todo o `src/main` do `core`). Os testes do consumidor ficam em `core/src/test/java/br/com/db1/poc/inbox`. No Spring: `InboxAutoConfigurationTest` e `SpringInboxTransactionExecutorTest` com `ApplicationContextRunner`, H2 em memória e uma `MessageSource` simulada.
- **Integração (`*IT`, failsafe, Testcontainers, com Docker)** — RabbitMQ, Toxiproxy, H2 em arquivo e SQL Server: fluxo feliz, duplicata, corrida do mesmo `messageId` (A-F2 em H2 e em SQL Server), queda entre commit e ack, mensagem veneno até a DLQ com as seguintes da chave (A-F6), ordem por agregado e memória estável, com `-Xmx256m -XX:MaxDirectMemorySize=64m`.
- **Infraestrutura de teste já existente**: surefire e failsafe, JUnit Jupiter, Testcontainers 2.0.5 (RabbitMQ, SQL Server, Toxiproxy), `shared.support` (`TestInfrastructure`, `LogCapture`, `Await`, `HeapProbe`, `TestEnvelopes`). **A introduzir**: `InboxSqlServerIT` e `InboxBrokerIT` (bases dos `*IT` da inbox, em `br.com.db1.poc.inbox.support`, sobre o `TestInfrastructure` do `shared`), `com.h2database:h2` em escopo `test`: 2.2.224 no `core` (última linha com bytecode Java 8) e a versão do BOM do Spring Boot em cada adaptador (piso Java 17), `InMemoryInboxDialect` e uma `MessageSource` simulada como suporte de teste, e `H2Db` (suporte de `*IT` com H2 em arquivo).
- **Matriz de JDKs**: os `*Test` e `*IT` do `core` rodam nos JDKs 8, 11, 17, 21 e 25 pelo procedimento `-Djvm` já documentado; o H2 2.2.224 foi verificado nesses JDKs (research).

## Impacto na documentação autoritativa

Desvio deliberado: a decisão do requisitante desta unidade é implementar o consumidor no `mensageria-outbox-core`, no mesmo artefato e no mesmo contexto de beans da outbox, e cada texto abaixo declara o consumidor como módulo isolado e independente, com envelope próprio e Java 21. Cada item vira tarefa de atualização em `tasks.md`:

- **`.agents/skills/mensageria-inbox/SKILL.md`** — visão geral e fronteira: o consumidor é parte do `mensageria-outbox-core` e usa o `Envelope` do pacote `shared`, em vez de "módulo isolado e autossuficiente com a sua própria definição do envelope"; a seção "Integrações e dependências externas" cita o SQL Server como banco da inbox além do H2; o R10b recebe o limite do bloqueio só em memória (P7); a seção "Parâmetros configuráveis" lista os padrões da unidade; o ponto em aberto sobre compatibilidade de Micrometer e Testcontainers com a plataforma mínima passa a citar a matriz de JDKs; a referência ao contrato da mensagem permanece.
- **`.agents/skills/mensageria-inbox/references/technical-dependencies.md`** — a entrada "Java 21 e Maven multi-módulo" passa a Java 8 como piso do `core` e adaptadores Spring com piso 17; o H2 e o SQL Server como bancos da inbox.
- **`AGENTS.md`** — visão geral, "Stack principal", "Estrutura do repositório" (a linha `mensageria-inbox/` na raiz deixa de existir e o consumidor consta do `core`), convenções arquiteturais (independência e envelope próprio; o Spring do consumidor), "Comandos essenciais".
- **`.agents/maps/functional-map.md`** — domínio 2: estado de implementação, dependência entre domínios com o `mensageria-outbox` (mesmo artefato), "Java 21 e Maven multi-módulo" e a ordem de implementação (o consumidor deixa de ser independente do artefato da outbox); parágrafo de abertura.
- **`.agents/context/discovery-answers.md`** — D1, D2, D9 e D14 recebem a decisão de 2026-10-21 (consumidor no `core`, no mesmo artefato e no mesmo contexto de beans, Java 8 como piso); entra a linha g13 no registro das decisões humanas.
- **`.agents/skills/mensageria-outbox/SKILL.md`** e **`.agents/skills/mensageria-outbox/references/technical-dependencies.md`** — a referência de que a outbox é isolada do consumidor passa a registrar que os dois lados compartilham o artefato `core`, com `br.com.db1.poc.outbox` e `br.com.db1.poc.inbox` independentes entre si e o pacote `br.com.db1.poc.shared` comum aos dois.
- **`.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md`** — a frase "cada módulo carrega a própria definição do envelope" passa a registrar que produtor e consumidor usam o `Envelope` do pacote `shared`; o corpo do contrato permanece.
- **Skills do código comum e dos dois adaptadores Spring** (`mensageria-outbox-spring-boot-shared`, `mensageria-outbox-spring-boot3`, `mensageria-outbox-spring-boot4`) e os `AGENTS.md` e `README.md` dessas pastas — a auto-configuração do consumidor passa a existir; entram as chaves `inbox.consumer.*` e o registro no `AutoConfiguration.imports`; as pendências P1 do código comum e P2 dos adaptadores deixam de ser pendência e saem de `pendencias.md` quando a unidade entregar o consumidor.
- **`.agents/context/pendencias.md`** (deste componente) — P6 sai da lista ao entregar a unidade; P7 permanece.
- **`README.md` do componente** — seção do consumidor (documentação do módulo, não autoritativa).
- **`demooutboxinbox`** — nenhuma alteração nesta unidade; a substituição do consumo próprio é a P1 de `../../../../demooutboxinbox/.agents/context/pendencias.md`.
