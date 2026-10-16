# Tarefas: Mensageria Outbox — compatibilidade com Java 8

Os casos de teste citados (`TC-n`) estão em [`test-cases.md`](./test-cases.md). Os testes de cada tarefa nascem na própria tarefa. Enquanto o `pom.xml` do módulo não declara `--release 8` (T8), as tarefas T2 a T7 compilam e testam o módulo com `-Dmaven.compiler.release=21`; cada uma deixa os arquivos que converte sem erro de compilação no nível Java 8, e o conjunto fecha o build em Java 8 na T8. As tarefas cujos `*IT` usam SQL Server e RabbitMQ têm como precondição o Docker em execução.

- [x] **T1. Atualizar a documentação autoritativa**
  - Depends on: none
  - Alvo 1 — `.agents/skills/mensageria-outbox/references/technical-dependencies.md`, seção "Dependências externas relevantes", item "Java 21 e Maven multi-módulo": declarar Java 8 (bytecode e API) como piso do módulo e a matriz de JDKs 8, 11, 17, 21 e 25 em que a suíte é verificada, mantendo Maven multi-módulo e o adaptador fino para Spring Boot 3.x. Origem: histórias 1 e 4.
  - Alvo 2 — `.agents/skills/mensageria-outbox/SKILL.md`, seção "Pontos em aberto", item sobre a compatibilidade de Micrometer e Testcontainers com a versão mínima de plataforma: registrar que a compatibilidade é verificada pela suíte do módulo nos JDKs 8, 11, 17, 21 e 25. Origem: história 4.
  - Alvo 3 — `AGENTS.md`, seções "Stack principal", "Estrutura do repositório" (linha do `pom.xml` e do módulo `mensageria-outbox`) e "Comandos essenciais": Java 21 permanece a versão mínima dos demais módulos e o `mensageria-outbox` tem Java 8 como piso; o build roda em JDK 21; a suíte do módulo executa em cada JDK da matriz com `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java`. Origem: histórias 1 e 4.
  - Alvo 4 — `.agents/maps/functional-map.md`, parágrafo de abertura (lista das decisões transversais) e seção "1. Mensageria Outbox", item "Java 21 e Maven multi-módulo" de "Dependências externas relevantes": o domínio Mensageria Outbox usa Java 8 como piso; os demais domínios seguem em Java 21. Origem: história 1.
  - Alvo 5 — `.agents/context/discovery-answers.md`, tabela "Decisões transversais validadas" (D1, D3 e D6) e linha g1 do "Registro das decisões humanas": registrar a exceção do `mensageria-outbox` (Java 8 como piso, consumido pelo adaptador de Pagamentos em Spring Boot 3.x) e a compatibilidade de Testcontainers e Micrometer confirmada pela matriz 8, 11, 17, 21 e 25. Origem: histórias 1, 2 e 4.
  - Alvo 6 — `.agents/skills/ambiente-docker-sqlserver-rabbitmq/SKILL.md`, seção "Testes de integração (Testcontainers)", item de dependências em escopo de teste, e `.agents/skills/testes-de-falha-resiliencia/SKILL.md`, seção "Visão geral", parágrafo "Ambiente (decisão D6)": a frase "compatibilidade das versões … com Java 21 deve ser confirmada na implementação" passa a citar a compatibilidade confirmada pelo módulo `mensageria-outbox` nos JDKs 8, 11, 17, 21 e 25. Origem: história 4.
  - Alvo 7 — `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/api-modulo.md`, seção "Consulta de pendentes (somente leitura)": `PendingStats` é uma classe final com os acessores `count()` e `oldestAge()`, no lugar da declaração `record`. Origem: história 3, critério dos nomes de acesso preservados.

- [x] **T2. Tipos de valor do módulo em Java 8: `Envelope`, `PublishableMessage`, `OutboxRow`, `PendingSummary` e `PendingStats`**
  - Depends on: T1
  - Converter os cinco registros em classes finais com os mesmos acessores, construtores, validações, `equals`, `hashCode` e `toString` (comparação por componente com `Objects.equals`; formato `Nome[componente=valor, ...]`; `Envelope` mantém a cópia do payload na entrada e na saída e o `equals` por conteúdo do payload).
  - Converter o `EnvelopeTest` para Java 8 e acrescentar as asserções de equivalência dos tipos (acessores, validações de `PendingStats`, `equals`, `hashCode`, `toString`, `PublishableMessage.from`, `OutboxRow.aggregateKey`). Casos: TC-13, TC-14, TC-15.

- [x] **T3. `RelayConfig`, `BackoffPolicy` e instalador de esquema em Java 8**
  - Depends on: T1
  - Criar o pacote `internal` com `Texts` (`isBlank` e `strip` com a definição de espaço em branco do Java 11), usado por `RelayConfig` e `OutboxSchemaInstaller`; converter `RelayConfig` (classe final com os 14 acessores, as mesmas validações e `fromProperties`, `switch` clássico por `String` nas durações), `BackoffPolicy.State` (classe aninhada final) e a leitura do script em `OutboxSchemaInstaller` (laço de leitura no lugar de `readAllBytes`).
  - Converter `RelayConfigTest`, `BackoffPolicyTest` e `OutboxSchemaInstallerIT` para Java 8 e acrescentar os casos de espaço em branco não ASCII em `RelayConfigTest`. Casos: TC-15 (`BackoffPolicy`), TC-16, TC-17, TC-20.

- [x] **T4. `switch`, SQL e topologia RabbitMQ em Java 8**
  - Depends on: T1
  - Pré-requisito nomeado: antes de editar `SqlServerOutboxDialect`, guardar a saída de `javap -p -constants` das constantes de texto SQL compiladas com `-Dmaven.compiler.release=21` na revisão atual.
  - Trocar os `switch` por expressão de `PendingQuery`, `RelayFactory` e `OutboxRetention` por `switch` de instrução com `default` que lança `IllegalStateException`; trocar os blocos de texto de `SqlServerOutboxDialect` por concatenação que reproduz o valor exato das constantes; trocar `Map.of` de `RabbitTopology` por `Collections.singletonMap`.
  - Converter `OutboxRetentionTest` para Java 8; conferir que a saída de `javap -p -constants` depois da conversão é idêntica à guardada. Casos: TC-18, TC-19, TC-21.

- [x] **T5. Testes unitários e suporte de teste em Java 8**
  - Depends on: T1
  - Converter para Java 8 `OutboxMetricsTest`, `RabbitMessagePublisherTest` (registro de teste `Published` e `switch` por regra), `RelayAlgorithmTest`, `RelayMemoryBoundTest`, `RelayObservabilityTest`, `PackageBoundaryTest`, `ComposeImagesTest`, `InMemoryDialect`, `LogCapture`, `ScriptedPublisher` (registro de teste `Attempt`), `TestEnvelopes`, `HeapProbe`, `Await` e `Images`, mantendo as asserções.
  - Trocar `Path.of`, `List.of`, `Stream.toList`, `var`, `String.repeat`, `Files.readString` e `switch` por regra pelos equivalentes de Java 8, sem remover nem enfraquecer asserção. Casos: TC-3, TC-15, TC-18, TC-23, TC-24 (`RelayMemoryBoundTest`).

- [x] **T6. Infraestrutura de testes de integração e `*IT` de gravação, publicação e ordem em Java 8**
  - Depends on: T1
  - Converter para Java 8 `BrokerIT` (registro `Received`), `OutboxDb` (`switch` por regra e blocos de texto), `RelayIT`, `SqlServerIT` (bloco de texto), `TestInfrastructure` (registro `Broker`), `RabbitMessagePublisherIT`, `OutboxWriterIT`, `RelayPublicationIT`, `RelayOrderingIT` e `ReadPointRelayIT`, mantendo as asserções.
  - Precondição: Docker em execução para executar os `*IT` convertidos. Casos: TC-21, TC-22.

- [x] **T7. `*IT` de backoff, lease, memória, observabilidade e retenção em Java 8**
  - Depends on: T1
  - Converter para Java 8 `MinActiveRowversionIT` (bloco de texto com `formatted` e `Path.of`), `RelayBackoffIT` (`switch` por regra e `var`), `RelayLeaseIT` (`var`), `RelayMemoryIT` (bloco de texto, `formatted` e `Stream.toList`), `RelayObservabilityIT`, `RelayObservationsIT` (bloco de texto, `Path.of` e `Set.of`) e `RelayRetentionIT` (`switch` por regra e `List.of`), mantendo as asserções.
  - Precondição: Docker em execução para executar os `*IT` convertidos. Casos: TC-22, TC-24 (`RelayMemoryIT`).

- [x] **T8. Build do módulo em `--release 8` com guardas de regressão**
  - Depends on: T2, T3, T4, T5, T6, T7
  - Pré-requisito nomeado: dependência `org.codehaus.mojo:extra-enforcer-rules` 1.12.1 no `maven-enforcer-plugin` do módulo.
  - `pom.xml` do módulo: declarar a raiz como pai, remover as versões literais das dependências, definir `maven.compiler.release` 8 (com `-Xlint:-options`), `mssql-jdbc.version` 13.4.0.jre8 e `logback.version` 1.3.16, e acrescentar a execução `enforce-bytecode` (fase `validate`) com a regra `enforceBytecodeVersion` (`maxJdkVersion` 8, `ignoreClasses` das classes `io.netty.util.internal.shaded.org.jctools.queues.varhandle.*`). `pom.xml` da raiz: o `requireJavaVersion` e a sua mensagem passam a usar a propriedade `maven.build.jdk.min` (21), e `maven.compiler.release` permanece 21.
  - `BytecodeLevelTest` novo (versão principal 52 em `target/classes` e `target/test-classes`) e `PackageBoundaryTest` ampliado (nenhum import `sun.*`, `com.sun.*` ou `jdk.internal.*` e nenhuma chamada a `setAccessible` em `src/main`).
  - Build completo `mvn -pl mensageria-outbox -am clean verify` em JDK 21 com `--release 8`. Casos: TC-1, TC-2, TC-3, TC-4, TC-6, TC-7, TC-10, TC-11, TC-12, TC-32, TC-33, TC-34, TC-35.

- [x] **T9. Procedimento da matriz de JDKs e `README.md` do módulo**
  - Depends on: T8
  - `mensageria-outbox/README.md`: descrição do módulo como biblioteca com piso Java 8; tabela "Versões fixadas" com Java 8 como piso, a matriz de JDKs 8, 11, 17, 21 e 25, `mssql-jdbc` 13.4.0.jre8 (runtime), JUnit 5.14.4 e Logback 1.3.16 (teste) e as demais versões inalteradas; procedimento de teste com `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java` para cada JDK, o comportamento sem Docker e a exigência de JDK 21 para o build. Casos: TC-5, TC-8, TC-9, TC-25, TC-26, TC-27, TC-28, TC-29, TC-30, TC-31.
