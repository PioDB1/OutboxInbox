# Tarefas: Mensageria Outbox — núcleo da inbox no `mensageria-outbox-core`

Os casos de teste citados (`TC-n`) estão em [`test-cases.md`](./test-cases.md). Os testes de cada tarefa nascem na própria tarefa. O código do `core` é Java 8 (`--release 8`, enforcer de bytecode 8); o código comum do Spring é Java 17. As tarefas cujos `*IT` usam RabbitMQ, Toxiproxy ou SQL Server têm como precondição o Docker em execução. Os artefatos já estão em 0.4.0-SNAPSHOT; a verificação final do build é a T10. A unidade [`004-mensageria-outbox-pacote-shared`](../004-mensageria-outbox-pacote-shared/spec.md) é precondição de todas as tarefas: ela cria o pacote `br.com.db1.poc.shared` que o consumidor usa.

- [x] **T1. Atualizar a documentação autoritativa**
  - Depends on: none
  - Alvo 1 — `.agents/skills/mensageria-inbox/SKILL.md`: visão geral e fronteira (o consumidor é parte do `mensageria-outbox-core` e usa o `Envelope` do pacote `shared`), R10b (o bloqueio de chave vive só em memória enquanto a P7 não é decidida), "Parâmetros configuráveis" (padrões da unidade), "Integrações e dependências externas" (SQL Server além do H2) e "Pontos em aberto" (compatibilidade de Micrometer e Testcontainers verificada pela suíte do `core` nos JDKs 8, 11, 17, 21 e 25). Origem: histórias 1, 5, 6 e 8.
  - Alvo 2 — `.agents/skills/mensageria-inbox/references/technical-dependencies.md`: a entrada "Java 21 e Maven multi-módulo" passa a Java 8 como piso do `core` e adaptadores Spring com piso 17; H2 e SQL Server como bancos da inbox. Origem: histórias 6 e 9.
  - Alvo 3 — `AGENTS.md`: visão geral, "Stack principal", "Estrutura do repositório" (o consumidor consta do `core`; sai a linha `mensageria-inbox/`), convenções arquiteturais (os dois lados no mesmo artefato; Spring do consumidor), "Comandos essenciais" e a estrutura de `.agents/` (`pendencias.md`). Origem: histórias 9 e 10.
  - Alvo 4 — `.agents/maps/functional-map.md`: parágrafo de abertura, domínio 2 (estado de implementação, dependência do artefato da outbox, "Java 21 e Maven multi-módulo", confiança e lacunas) e "Sugestão de ordem de implementação". Origem: história 10.
  - Alvo 5 — `.agents/context/discovery-answers.md`: D1, D2, D9 e D14 recebem a decisão de 2026-10-21 (consumidor no `core`, mesmo artefato, mesmo contexto de beans, Java 8 como piso) e entra a linha g13 no registro das decisões humanas, com a resposta de 2026-10-21 sobre o estado das chaves bloqueadas (P7). Origem: histórias 5 e 10.
  - Alvo 6 — `.agents/skills/mensageria-outbox/SKILL.md` e `.agents/skills/mensageria-outbox/references/technical-dependencies.md`: a referência de isolamento do consumidor passa a registrar que os dois lados compartilham o artefato `core` e o pacote `shared`, e `outbox` e `inbox` são independentes entre si. Origem: história 10.
  - Alvo 7 — `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md`: o parágrafo de abertura passa a registrar que produtor e consumidor usam o `Envelope` do pacote `shared`. Origem: história 1.
  - Alvo 8 — `../mensageria-outbox-spring-boot-shared/.agents/skills/mensageria-outbox-spring-boot-shared/SKILL.md` (e a referência de dependências técnicas), `../mensageria-outbox-spring-boot3/.agents/skills/mensageria-outbox-spring-boot3/SKILL.md` e `../mensageria-outbox-spring-boot4/.agents/skills/mensageria-outbox-spring-boot4/SKILL.md`: a auto-configuração do consumidor, as chaves `inbox.consumer.*` e o registro no `AutoConfiguration.imports`. Origem: história 9.

- [x] **T2. Configuração, backoff, envelope de leitura, métricas e porta de consumo**
  - Depends on: none
  - No pacote `br.com.db1.poc.inbox`: `InboxConfig` (com `fromProperties` sobre `shared.config.PropertyReader` e as validações da história 5, inclusive `NaN` e infinito), `RetryBackoff`, `EnvelopeReader` com `InvalidEnvelopeException` e as interfaces `MessageSource` e `Delivery`; em `br.com.db1.poc.inbox.metrics`, `InboxMetrics` com as três constantes de nome.
  - Testes: `InboxConfigTest`, `RetryBackoffTest`, `EnvelopeReaderTest` (TC-2 e as variações de TC-3 até o `Envelope` rejeitar), `InboxMetricsTest`. Casos: TC-2, TC-23, TC-30 (registro dos contadores) e a parte de leitura de TC-1 e TC-3.

- [x] **T3. Dialetos H2 e SQL Server, scripts de esquema e instalador**
  - Depends on: none
  - Pré-requisito nomeado: dependência `com.h2database:h2` 2.2.224 em escopo `test` no `core` (bytecode Java 8; a versão 2.3.x é recusada pelo enforcer) e a versão do BOM do Spring Boot, em escopo `test`, nos `pom.xml` de `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`.
  - `br.com.db1.poc.inbox.dialect.InboxDialect`, `SqlServerInboxDialect` e `H2InboxDialect` (a inserção do H2 grava `consumed_at` em UTC), os dois `schema.sql` de [`data-model.md`](./data-model.md), `br.com.db1.poc.inbox.schema.InboxSchemaInstaller` (sobre `shared.schema.SqlScripts`), o reconhecimento da violação de chave do SQL Server por `shared.dialect.SqlServerErrors`, com os scripts em `br/com/db1/poc/inbox/schema/<dialeto>/schema.sql`.
  - Testes: `InboxDialectH2Test`, `InboxSchemaInstallerH2Test`, `InboxSqlServerIT` (base dos `*IT` da inbox com SQL Server, em `br.com.db1.poc.inbox.support`, sobre o `TestInfrastructure` do `shared`) e `InboxDialectSqlServerIT`. Casos: TC-26, TC-27, TC-28, TC-29.

- [x] **T4. Transação local, dedup e processamento de uma mensagem**
  - Depends on: T2, T3
  - `InboxMessageHandler`, `InboxTransactionExecutor`, `JdbcInboxTransactionExecutor` (pacote `br.com.db1.poc.inbox`) e o processamento de uma entrega (consulta do `messageId`, inserção na inbox, tratador, duplicata por violação da chave primária reconhecida pelo dialeto, recusa de conexão em autocommit), sem fila por chave e sem retry. O suporte de teste `InMemoryInboxDialect` e uma `MessageSource` simulada entram aqui.
  - Testes: `InboxProcessingTest` sobre H2 em memória. Casos: TC-5, TC-6 (primeira tentativa), TC-7, TC-9, TC-10 (versão H2 em `*Test`), TC-12.

- [x] **T5. Consumidor com fila de execução por chave, ack depois do commit, métricas e MDC**
  - Depends on: T4
  - `KeyedExecutor` (uma fila por `aggregateKey`, `workers` threads, liberação da chave vazia, chave reservada `<sem-aggregateKey>`), `InboxConsumer` (`start`, `close`, ack só depois do commit, falha de ack registrada em log e não contada como falha de processamento, duplicata com ack e `inbox.duplicates.discarded`) e o MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` em volta de cada tentativa (`shared.logging.MessageMdc`). A falha de processamento nesta tarefa leva a mensagem à DLQ sem retry (comportamento substituído na T6).
  - Testes: `KeyedExecutorTest`, `InboxConsumerTest` (ordem, paralelismo, limite em voo na `MessageSource` simulada, ack após commit, falha de ack), `InboxMdcTest` com `LogCapture`. Casos: TC-8, TC-13, TC-14, TC-15 (parte simulada), TC-16, TC-31, TC-32, TC-9 e TC-10 pelo consumidor.

- [x] **T6. Retry com backoff, DLQ e chaves bloqueadas**
  - Depends on: T5
  - Retry na memória com a chave retendo a vez e o reagendamento fora das threads de trabalho; `BlockedKeys` e `InMemoryBlockedKeys`; envio à DLQ ao exceder `max-attempts`, e das mensagens seguintes da chave bloqueada (aguardando ou novas), com repetição da publicação na DLQ pelos intervalos do backoff quando a `MessageSource` falha; métricas `inbox.process.retries` e `inbox.dlq.messages`.
  - Testes: `InboxRetryDlqTest` com relógio e `MessageSource` simulados. Casos: TC-3 (até a DLQ), TC-6, TC-17, TC-18, TC-20 (parte simulada), TC-21, TC-24, TC-25, TC-30.

- [x] **T7. Adaptador RabbitMQ e fluxos de integração do consumo**
  - Depends on: T6
  - `br.com.db1.poc.inbox.rabbit.RabbitMessageSource` (`queueDeclarePassive` das duas filas, `basicQos`, `basicConsume` com `autoAck=false`, conversão de `LongString` para `String`, `ack` e `requeue` pelo canal da entrega com lock, `deadLetter` com confirm em canal próprio e `mandatory=true`) e `br.com.db1.poc.shared.rabbit.RabbitTopology.declareQuorumQueue(Channel, String)`. O `PackageBoundaryTest` ampliado pela unidade 004 cobre o código novo; os testes do consumidor ficam em `core/src/test/java/br/com/db1/poc/inbox`.
  - Testes: `RabbitMessageSourceTest` (conversão dos headers, argumentos da DLQ) e `*IT` com `InboxBrokerIT` (base nova em `br.com.db1.poc.inbox.support`, sobre o `TestInfrastructure` do `shared`) e H2 em arquivo: `InboxFlowIT` (fluxo feliz, duplicata, DLQ com headers), `InboxPoisonMessageIT`. Casos: TC-1, TC-4, TC-19, TC-20, TC-22, TC-34.

- [x] **T8. Cenários de falha, ordem e memória sobre SQL Server e H2**
  - Depends on: T7
  - `*IT` com Toxiproxy e SQL Server: `InboxCrashBetweenCommitAndAckIT` (A-F2), `InboxRaceIT` (corrida do mesmo `messageId`), `InboxOrderingIT` (ordem por agregado com RabbitMQ), `InboxMemoryIT` (10 000 mensagens, `-Xmx256m -XX:MaxDirectMemorySize=64m`, como o `RelayMemoryIT`); suporte `H2Db` para o H2 em arquivo.
  - Precondição: Docker em execução. Casos: TC-10 (SQL Server), TC-11, TC-13 (com RabbitMQ), TC-15 (com broker), TC-33.

- [x] **T9. Auto-configuração Spring do consumidor nas duas linhas do Spring Boot**
  - Depends on: T7
  - Em `mensageria-outbox-spring-boot-shared`: `InboxAutoConfiguration` (todo bean com `@ConditionalOnMissingBean`, `@Import` da `OutboxRabbitConnectionFactoryConfiguration`), `InboxProperties` (`inbox.consumer.*` com os padrões do plano), `SpringInboxTransactionExecutor`, `InboxConsumerLifecycle` (`SmartLifecycle`) e a detecção do dialeto pelo produto do `DataSource`. Em `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`: a linha `br.com.db1.poc.outbox.spring.InboxAutoConfiguration` no `AutoConfiguration.imports`.
  - Testes: `InboxAutoConfigurationTest` e `SpringInboxTransactionExecutorTest` com `ApplicationContextRunner`, H2 em memória e `MessageSource` simulada, executados nos dois módulos. Casos: TC-35, TC-36, TC-37, TC-38, TC-39, TC-40.

- [x] **T10. README, ADR 0002, pendências e verificação final do build**
  - Depends on: T8, T9
  - `mensageria-outbox/README.md`: seção do consumidor (dependência, tabela e script, fila e DLQ, tratador, transação, `inbox.consumer.*`, métricas, limites, limite do bloqueio de chaves da P7), tabela de artefatos e "Versões fixadas" (H2 de teste, `inbox`); READMEs de `mensageria-outbox-spring-boot-shared`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` com as chaves e o comportamento do consumidor; `docs/adr/0002-*.md` com as decisões de desenho do consumidor (research) e o índice `docs/adr/README.md`, conforme a skill `registro-de-adrs`.
  - `.agents/context/pendencias.md` deste componente: remover a P6; `../mensageria-outbox-spring-boot-shared/.agents/context/pendencias.md`: remover a P1; `../mensageria-outbox-spring-boot3/.agents/context/pendencias.md` e `../mensageria-outbox-spring-boot4/.agents/context/pendencias.md`: remover a P2 (a P7 e a P1 do demo permanecem).
  - Build completo `mvn clean verify` na raiz em JDK 21 e a suíte do `core` nos JDKs 8, 11, 17, 21 e 25 pelo procedimento `-Djvm`. Casos: TC-41, TC-42, TC-43 e a regressão da suíte existente (outbox) em todos os JDKs.
