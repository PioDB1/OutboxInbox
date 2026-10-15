# Plano: Mensageria Outbox — compatibilidade com Java 8

Responde ao "como" da [`spec.md`](./spec.md). Contexto, alternativas e evidências de cada decisão estão em [`research.md`](./research.md); aqui ficam os veredictos.

## Estado atual do código

O módulo `mensageria-outbox` está implementado em Java 21: 25 fontes em `src/main` (11 deles usam registros, `switch` por expressão, blocos de texto ou padrão de `instanceof`; 2 usam API posterior ao Java 8) e 36 arquivos em `src/test` (26 com sintaxe ou API posterior ao Java 8). O `pom.xml` do módulo não declara pai, repete as versões das dependências (JUnit 6.0.3, `mssql-jdbc` 13.4.0.jre11, Logback 1.5.38) e, sem a propriedade `maven.compiler.release` da raiz, compila em `-source 8` e falha. O `pom.xml` raiz declara `maven.compiler.release` 21, o enforcer (JDK 21 ou superior e Maven 3.9), `dependencyManagement` (`junit-bom` 5.14.4, `testcontainers-bom` 2.0.5, `amqp-client`, `micrometer-core`, `slf4j-api`, `mssql-jdbc` 12.10.2.jre11, Logback 1.5.38) e `pluginManagement`. A estação tem Maven 3.9.9 sobre JDK 21, JDK 25 e JDK 8, sem JDK 17 e sem Docker.

## Stack e estrutura

Maven multi-módulo conforme `AGENTS.md`; o módulo `mensageria-outbox` passa a ter nível de linguagem e de bytecode Java 8 e continua sem framework de aplicação. O build roda em JDK 21 e os JDKs 8, 11, 17, 21 e 25 executam a suíte. Pacote base `br.com.db1.poc.outbox`; a estrutura de pacotes não muda, com uma adição:

| Pacote | Conteúdo adicionado |
|---|---|
| `internal` | `Texts`: `isBlank` e `strip` com a definição de espaço em branco do Java 11, usados por `relay.RelayConfig` e `schema.OutboxSchemaInstaller`; não faz parte da API do módulo |

## Decisões técnicas

Contexto, alternativas e evidência de cada linha em [`research.md`](./research.md).

- **Compilação**: `--release 8` pela propriedade `maven.compiler.release` = 8 no `pom.xml` do módulo, com `-Xlint:-options`; vale para o código principal e o de teste.
- **`pom.xml` do módulo**: declara a raiz como pai, remove as versões literais e sobrescreve só `maven.compiler.release` (8), `mssql-jdbc.version` (13.4.0.jre8) e `logback.version` (1.3.16).
- **Enforcer da raiz**: o `requireJavaVersion` passa a usar a propriedade `maven.build.jdk.min` = 21; `maven.compiler.release` permanece 21 na raiz para os módulos futuros.
- **Dependências**: `mssql-jdbc` 13.4.0.jre8 (`runtime`), JUnit 5.14.4 (já gerenciado pela raiz), Logback 1.3.16 (teste); `amqp-client` 5.37.0, `micrometer-core` 1.17.1, `slf4j-api` 2.0.20 e Testcontainers 2.0.5 permanecem.
- **Registros**: `Envelope`, `PublishableMessage`, `OutboxRow`, `OutboxDialect.PendingSummary`, `PendingStats`, `RelayConfig` e `BackoffPolicy.State` viram classes finais com os mesmos acessores, validações, `equals`, `hashCode` e `toString`.
- **`switch` por expressão**: `switch` de instrução com `return` por `case` e `default` que lança `IllegalStateException` (`PendingQuery`, `RelayFactory`, `OutboxRetention`); `switch` clássico por `String` em `RelayConfig`.
- **Blocos de texto SQL**: concatenação com `\n` que reproduz o valor exato das constantes de `SqlServerOutboxDialect`, conferido por `javap -p -constants` antes e depois.
- **Demais APIs**: `Texts.isBlank` e `Texts.strip`, laço de leitura no lugar de `readAllBytes`, `Collections.singletonMap` no lugar de `Map.of`; nos testes, `Paths.get`, `Arrays.asList`, `Collections.emptyList`, `Collectors.toList`, tipos explícitos no lugar de `var`, `String.format`, `Files.readAllBytes`.
- **Guarda de bytecode das dependências**: regra `enforceBytecodeVersion` do `extra-enforcer-rules` 1.12.1 com `maxJdkVersion` 8, na execução `enforce-bytecode` (fase `validate`) do módulo, com `ignoreClasses` para as classes `io.netty.util.internal.shaded.org.jctools.queues.varhandle.*`.
- **Guarda no código**: `PackageBoundaryTest` também proíbe imports `sun.*`, `com.sun.*`, `jdk.internal.*` e chamadas a `setAccessible` em `src/main`; `BytecodeLevelTest` afirma versão principal 52 em todo `.class` de `target/classes` e `target/test-classes`.
- **Execução em cada JDK**: `-Djvm=<JDK_HOME>/bin/java` (propriedade lida pelo surefire e pelo failsafe), um `mvn -pl mensageria-outbox verify` por JDK da matriz, com o procedimento no `README.md` do módulo.

## Modelo de dados

Dispensado: a unidade não toca persistência; o texto das consultas SQL, o esquema e o script `schema.sql` permanecem idênticos.

## Contratos externos

Dispensado: o contrato da mensagem publicada e os nomes da API Java pública permanecem, e nenhum contrato novo surge. Os tipos que deixam de ser `record` constam do contrato `api-modulo.md` da unidade 001; a revisão entra em "Impacto na documentação autoritativa".

## Interface

Dispensada: o módulo não tem telas nem fluxos de interface.

## Estratégia de testes

- **Unitário (`*Test`, surefire, sem Docker)** — todos os testes existentes mantêm as asserções e passam em JDK 8, 11, 17, 21 e 25; `EnvelopeTest` e `RelayConfigTest` cobrem a equivalência dos tipos convertidos (validações, `equals`, `hashCode`, `toString`); `PackageBoundaryTest` ampliado e `BytecodeLevelTest` novo cobrem os critérios de bytecode 52 e de ausência de internos do JDK. Cada tarefa que converte código inclui a conversão dos testes correspondentes.
- **Integração (`*IT`, failsafe, Testcontainers)** — mantêm as asserções, com os limites `-Xmx256m -XX:MaxDirectMemorySize=64m`; exigem Docker em execução e são ignorados sem ele.
- **Infraestrutura de teste já existente**: surefire e failsafe 3.6.0, JUnit Jupiter, Testcontainers 2.0.5, Toxiproxy, `LogCapture`. **A introduzir**: JUnit 5.14.4 e Logback 1.3.16 no lugar das versões atuais, a regra `enforceBytecodeVersion`, `BytecodeLevelTest` e o procedimento `-Djvm` no README.
- **Matriz**: o ciclo `verify` roda nos cinco JDKs; os `*IT` só contam como verificados em um ambiente com Docker.

## Impacto na documentação autoritativa

Desvio deliberado: o piso de Java do `mensageria-outbox` é 8 (decisão do requisitante desta unidade), enquanto os textos abaixo declaram Java 21 como plataforma do módulo ou da PoC. Cada item vira tarefa de atualização em `tasks.md`:

- **`.agents/skills/mensageria-outbox/references/technical-dependencies.md`** — a entrada "Java 21 e Maven multi-módulo" passa a declarar Java 8 (bytecode e API) e a matriz de JDKs 8, 11, 17, 21 e 25 para este domínio.
- **`.agents/skills/mensageria-outbox/SKILL.md`** — o ponto em aberto sobre a compatibilidade de Micrometer e Testcontainers com a versão mínima de plataforma passa a registrar que a verificação é feita pela suíte nos cinco JDKs.
- **`AGENTS.md`** — a "Stack principal" e a árvore de estrutura (`pom.xml` "Java 21") indicam Java 21 como versão mínima dos módulos, com a exceção do `mensageria-outbox` em Java 8; a seção de comandos documenta `-Djvm` para a matriz.
- **`.agents/maps/functional-map.md`** — a dependência "Java 21 e Maven multi-módulo" do domínio Mensageria Outbox passa a Java 8.
- **`.agents/context/discovery-answers.md`** — D1, D3, D6 e o registro da lacuna g1 recebem a exceção do `mensageria-outbox`.
- **`.agents/skills/ambiente-docker-sqlserver-rabbitmq/SKILL.md`** e **`.agents/skills/testes-de-falha-resiliencia/SKILL.md`** — a frase "compatibilidade com Java 21 deve ser confirmada na implementação" passa a citar a matriz de JDKs confirmada pelo módulo.
- **`.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/api-modulo.md`** — a assinatura `public record PendingStats` e as demais declaradas como `record` passam a classes finais com os mesmos acessores.
- **`mensageria-outbox/README.md`** (documentação do módulo) — descrição "biblioteca Java 21", tabela "Versões fixadas" e o procedimento da matriz.
