# Tarefas: Mensageria Outbox — código comum da outbox e da inbox no pacote `shared`

Os casos de teste citados (`TC-n`) estão em [`test-cases.md`](./test-cases.md). Os testes de cada tarefa nascem na própria tarefa. O código do `core` é Java 8 (`--release 8`, enforcer de bytecode 8); o código comum do Spring é Java 17. Enquanto uma tarefa não termina, o módulo compila e passa nos testes com o que já foi movido: cada tarefa move e ajusta os imports de todos os arquivos afetados, de modo que o build fecha ao fim de cada uma. As tarefas cujos `*IT` usam SQL Server, RabbitMQ ou Toxiproxy têm como precondição o Docker em execução. Esta unidade precede a unidade 003.

- [x] **T1. Atualizar a documentação autoritativa**
  - Depends on: none
  - Alvo 1 — `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/api-modulo.md`: o pacote de `Envelope` (`br.com.db1.poc.shared.envelope`) e de `RabbitTopology` (`br.com.db1.poc.shared.rabbit`) e o pacote base da API (`br.com.db1.poc.outbox` e `br.com.db1.poc.shared`). Origem: história 5.
  - Alvo 2 — `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md`: a referência à topologia (`shared.rabbit.RabbitTopology`). Origem: história 5.
  - Alvo 3 — `AGENTS.md`, seção "Convenções arquiteturais": o pacote `shared` (código comum à outbox e à inbox, no mesmo artefato `core`) e a direção das dependências (`outbox` e `inbox` dependem de `shared`; `shared` e os dois lados não dependem uns dos outros). Origem: histórias 1 e 3.
  - Alvo 4 — `../mensageria-outbox-spring-boot-shared/.agents/skills/mensageria-outbox-spring-boot-shared/SKILL.md`: os pacotes de `Envelope` e `RabbitTopology`. Origem: história 5.

- [x] **T2. Pacotes `shared.envelope` e `shared.text`**
  - Depends on: none
  - Mover `Envelope` para `br.com.db1.poc.shared.envelope` e `Texts` para `br.com.db1.poc.shared.text`; ajustar os imports de `write`, `dialect`, `publish`, `relay`, `retention`, `lease`, `schema`, do código comum do Spring (`OutboxDespachante`, `OutboxGravada`, `OutboxGravador`, `OutboxAutoConfigurationTest`, `OutboxDespachanteTest`) e dos testes do `core`; mover `EnvelopeTest` e `TextsTest` para os pacotes de teste de `shared`.
  - Casos: TC-2, TC-3, TC-9, TC-18.

- [x] **T3. Suporte de teste genérico e `PackageBoundaryTest` ampliado**
  - Depends on: T2
  - Mover `Await`, `HeapProbe`, `LogCapture`, `Images`, `ComposeImagesTest`, `TestInfrastructure` e `TestEnvelopes` para `core/src/test/java/br/com/db1/poc/shared/support`, com os imports dos testes e dos `*IT` ajustados; `SqlServerIT`, `BrokerIT`, `OutboxDb`, `RelayIT` e `ScriptedPublisher` ficam em `outbox.support`. Ampliar o `PackageBoundaryTest` com as regras de direção entre `shared`, `outbox` e `inbox` e com `com.rabbitmq` aceito em `br/com/db1/poc/shared/rabbit`, `br/com/db1/poc/outbox/rabbit` e `br/com/db1/poc/inbox/rabbit`, mantendo Spring proibido.
  - Casos: TC-15, TC-16, TC-17, TC-9.

- [x] **T4. `shared.rabbit.RabbitTopology`**
  - Depends on: T3
  - Mover `RabbitTopology` para `br.com.db1.poc.shared.rabbit`; ajustar `RabbitMessagePublisher`, `BrokerIT`, `RabbitMessagePublisherIT`, os testes que a usam e o código comum do Spring (`OutboxAutoConfiguration`).
  - Casos: TC-8, TC-17, TC-10, TC-18.

- [x] **T5. `shared.config.PropertyReader` e `shared.logging.MessageMdc`**
  - Depends on: T2
  - Extrair de `RelayConfig` a leitura de texto, inteiro, decimal e duração para `PropertyReader` (prefixo no construtor, mesmas regras e mensagens) e fazer `RelayConfig.fromProperties` delegar a ele; extrair de `RelayRunner.publish` a abertura do MDC para `MessageMdc.open` e fazer o relay usá-lo.
  - Testes: `PropertyReaderTest`, `MessageMdcTest`; `RelayConfigTest` e `RelayObservabilityTest` existentes continuam passando. Casos: TC-4, TC-5, TC-11, TC-12.

- [x] **T6. `shared.schema.SqlScripts` e `shared.dialect.SqlServerErrors`**
  - Depends on: T2
  - Extrair de `OutboxSchemaInstaller` a leitura do script em lotes para `SqlScripts.readBatches(Class<?>, String)` e fazer o instalador usá-la; extrair de `SqlServerOutboxDialect` o reconhecimento de violação de chave para `SqlServerErrors.isDuplicateKey` e fazer o dialeto usá-lo.
  - Testes: `SqlScriptsTest`, `SqlServerErrorsTest`; `OutboxSchemaInstallerIT` e os `*IT` que dependem de duplicidade (`OutboxWriterIT`, `RelayPublicationIT`) continuam passando. Casos: TC-6, TC-7, TC-13.

- [x] **T7. README e verificação final**
  - Depends on: T1, T4, T5, T6
  - `mensageria-outbox/README.md` (imports e tabela de pacotes, incluindo o `shared`), `mensageria-outbox-spring-boot3/README.md` e `mensageria-outbox-spring-boot4/README.md` (imports de `Envelope` nos exemplos de uso). `.agents/context/pendencias.md` deste componente: remover a P8.
  - Build completo `mvn clean verify` na raiz em JDK 21 e a suíte do `core` nos JDKs 8, 11, 17, 21 e 25 pelo procedimento `-Djvm`. Casos: TC-1, TC-14, TC-18, TC-19 e a regressão da suíte existente.
