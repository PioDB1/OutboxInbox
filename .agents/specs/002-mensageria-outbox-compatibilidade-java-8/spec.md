# Spec: Mensageria Outbox — compatibilidade com Java 8

## Visão geral

Migração do módulo `mensageria-outbox` para o nível de linguagem e de bytecode do Java 8, mantendo o comportamento do domínio, para que a biblioteca rode do JDK 8 até as versões mais novas, com a suíte do módulo verificada em todas as versões LTS (8, 11, 17, 21 e 25).

## Domínio

- Slug: `mensageria-outbox`
- Skill: [`.agents/skills/mensageria-outbox/SKILL.md`](../../skills/mensageria-outbox/SKILL.md) (regras R1 a R27; esta unidade não altera nenhuma delas)
- Dependências técnicas do domínio: [`.agents/skills/mensageria-outbox/references/technical-dependencies.md`](../../skills/mensageria-outbox/references/technical-dependencies.md)
- Unidade anterior: [`001-mensageria-outbox-implementacao-modulo`](../001-mensageria-outbox-implementacao-modulo/spec.md), que implementou o módulo em Java 21.

## Estado atual do código

O módulo `mensageria-outbox` está implementado: 25 fontes em `src/main` (pacotes `envelope`, `dialect`, `relay`, `lease`, `publish`, `rabbit`, `retention`, `schema`, `metrics`, `write`) e testes `*Test` (surefire) e `*IT` (failsafe, Testcontainers) em `src/test`. O código usa recursos de linguagem do Java 9 a 21:

- **`record`:** `Envelope`, `PublishableMessage`, `OutboxRow`, `OutboxDialect.PendingSummary`, `PendingStats`, `RelayConfig` e `BackoffPolicy.State`.
- **Expressões e regras de `switch`:** `PendingQuery`, `RelayConfig`, `RelayFactory` e `OutboxRetention`.
- **Blocos de texto (`"""`):** as consultas SQL de `SqlServerOutboxDialect`.
- **Padrão de `instanceof`:** `Envelope.equals`.
- **APIs posteriores ao Java 8:** `String.isBlank` e `String.strip` (`RelayConfig`, `OutboxSchemaInstaller`), `InputStream.readAllBytes` (`OutboxSchemaInstaller`) e `Map.of` (`RabbitTopology`). Nos testes: `Path.of`, `Stream.toList`, `List.of` e `Map.of`, entre outros.

O `pom.xml` do módulo declara as versões das dependências de forma literal e não referencia o `pom.xml` raiz como pai; sem o `maven.compiler.release` da raiz, o compilador assume `-source 8` e `mvn -pl mensageria-outbox compile` falha nos recursos listados acima. O `pom.xml` raiz declara `maven.compiler.release` 21, `maven-enforcer-plugin` exigindo JDK 21 ou superior, `dependencyManagement` e `pluginManagement`, e agrega só este módulo.

Nível de bytecode das dependências do classpath de teste, conferido nos JARs:

| Dependência | Versão atual | Bytecode | Situação |
|---|---|---|---|
| `com.rabbitmq:amqp-client` | 5.37.0 | Java 8 | compatível |
| `io.micrometer:micrometer-core` (e `micrometer-commons`, `micrometer-observation`) | 1.17.1 | Java 8 | compatível |
| `org.slf4j:slf4j-api` | 2.0.20 | Java 8 | compatível |
| `org.testcontainers:*` (e `docker-java`, `toxiproxy-java`, `gson`, `netty`) | 2.0.5 | Java 8 | compatível |
| `com.microsoft.sqlserver:mssql-jdbc` | 13.4.0.jre11 | Java 11 | existe a variante `13.4.0.jre8` no Maven Central |
| `org.junit.jupiter:junit-jupiter` | 6.0.3 | Java 17 | a linha 5.14.x (5.14.4) tem bytecode Java 8 |
| `ch.qos.logback:logback-classic` | 1.5.38 | Java 11 | a linha 1.3.x (até 1.3.16) é a que declara Java 8 com SLF4J 2.0 |

O módulo é uma biblioteca sem executável próprio, sem uso por aplicação hospedeira ainda (Pagamentos não existe): o alvo da mudança é o produto desta unidade, não código morto.

## Escopo

**Dentro:**

- A biblioteca `mensageria-outbox` passa a ser compilada com `--release 8`, de modo que o mesmo artefato execute em JDK 8, 11, 17, 21 e 25.
- Comportamento do domínio inalterado: envelope, gravação na outbox, relay com as duas estratégias, lease, publicação com confirms, retry com backoff, retenção, métricas e logs.
- Interface pública do módulo preservada, para que a aplicação hospedeira use a biblioteca com as mesmas chamadas e os mesmos nomes de acesso.
- A suíte de testes do módulo (`*Test` e `*IT`) escrita em Java 8 e executada com sucesso nos JDKs 8, 11, 17, 21 e 25.
- Procedimento reproduzível e documentado para executar a suíte em cada um dos cinco JDKs.

**Fora:**

- Módulos `mensageria-inbox`, `pagamentos`, `extrato` e `reconciliacao`: mantêm Java 21 como versão mínima e não entram nesta unidade.
- Adaptador de Spring Boot 3.x do domínio Pagamentos, que exige Java 17 ou superior por conta do próprio framework.
- Mudança de regra de negócio, de contrato de mensagem, de esquema SQL ou de versão das imagens Docker (SQL Server, RabbitMQ, Toxiproxy).
- Suporte a versões não LTS do JDK além da matriz definida.
- Qualquer meta de latência ou de throughput.

## Fronteira do domínio

**Esta spec implementa:**

- Módulo Maven `mensageria-outbox`: `pom.xml` referenciando o `pom.xml` raiz como pai e fixando `--release 8` para o código principal e para o de teste, sem alterar o nível dos demais módulos.
- Tipos `Envelope`, `PublishableMessage`, `OutboxRow`, `OutboxDialect.PendingSummary`, `PendingStats`, `RelayConfig` e `BackoffPolicy.State` como classes finais com os mesmos nomes de acesso, construtores, `equals`, `hashCode` e `toString` que os registros atuais têm.
- Classes `PendingQuery`, `RelayFactory`, `OutboxRetention`, `RelayConfig`, `SqlServerOutboxDialect`, `OutboxSchemaInstaller` e `RabbitTopology`, com as construções posteriores ao Java 8 substituídas por equivalentes de Java 8.
- Dependência `mssql-jdbc` na variante `jre8` (escopo `runtime`), dependências de teste JUnit Jupiter na linha 5.14.x e Logback na linha 1.3.x.
- Fontes de teste do módulo (`src/test`) em Java 8.
- Mecanismo de execução da suíte nos JDKs 8, 11, 17, 21 e 25, e a sua documentação no `README.md` do módulo (seção "Versões fixadas" e procedimento de teste).
- Regra de enforcer ou verificação de build que impeça a volta de bytecode acima do Java 8 nas classes do módulo.

**Pertence a outros domínios (dependência entre domínios, não vira tarefa aqui):**

- Versão mínima de Java, `pom.xml` e enforcer dos módulos `mensageria-inbox`, `pagamentos`, `extrato` e `reconciliacao` → unidades desses domínios.
- Adaptador Spring Boot que consome o módulo → skill `pagamentos` (a ser criada).
- Escolha da estratégia de relay e ADR `0001` → domínio `mensageria-outbox`, fora desta unidade.

## Histórias de usuário

1. Como equipe de uma aplicação produtora ainda em JDK 8, quero incluir o `mensageria-outbox` como dependência, para adotar o padrão outbox sem atualizar a plataforma Java da aplicação.
2. Como equipe de uma aplicação produtora em JDK mais novo, quero usar o mesmo artefato do módulo em JDK 11, 17, 21 e 25, para atualizar a plataforma sem trocar a biblioteca.
3. Como equipe da aplicação produtora, quero que o comportamento do relay, da outbox e da publicação permaneça o descrito na skill do domínio, para que a mudança de plataforma não altere a garantia de entrega.
4. Como mantenedor da PoC, quero a suíte do módulo executada em JDK 8, 11, 17, 21 e 25 por um procedimento documentado, para comprovar a compatibilidade em vez de supô-la.
5. Como mantenedor da PoC, quero que o build barre código que dependa de recurso ou API posterior ao Java 8, para que a compatibilidade não se perca em mudanças futuras.

## Critérios de aceitação

**História 1 — Piso Java 8 (build e artefato):**

- Dado o repositório, quando se executa `mvn -pl mensageria-outbox -am clean verify` com JDK 21, então o módulo compila com `--release 8` (principal e teste) e o build termina com sucesso.
- Dado o JAR do módulo, quando se inspeciona o cabeçalho de cada arquivo `.class`, então a versão principal do formato é 52 (Java 8) em todos eles.
- Dado o código-fonte de `src/main` e de `src/test`, então nenhum fonte usa `record`, `var`, expressão ou regra de `switch`, bloco de texto, padrão de `instanceof`, `sealed`, nem API de biblioteca posterior ao Java 8; a compilação com `--release 8` é a prova.
- Dado o classpath de execução do módulo (escopos `compile` e `runtime`), então todas as classes de todos os JARs têm bytecode de no máximo Java 8, e o driver SQL Server é a variante `jre8`.
- Dado o JDK 8 e uma aplicação que carrega o módulo, quando o relay inicia, então a inicialização conclui sem `UnsupportedClassVersionError`, `NoSuchMethodError` ou `NoClassDefFoundError`.

**História 2 — Versões mais novas:**

- Dado o mesmo JAR, quando a suíte executa nos JDKs 11, 17, 21 e 25, então nenhuma falha decorre de API removida, de acesso reflexivo a internos do JDK ou de diferença de comportamento entre versões.
- Dado o código-fonte do módulo, então nenhum fonte importa `sun.*`, `com.sun.*` ou `jdk.internal.*`, nem usa `setAccessible` sobre classes do JDK.

**História 3 — Comportamento preservado (R1 a R27):**

- Dado `Envelope`, `PublishableMessage`, `OutboxRow`, `PendingStats`, `RelayConfig` e `OutboxDialect.PendingSummary`, quando um chamador usa os nomes de acesso (`messageId()`, `payload()`, `count()`, `oldestAge()`, `strategy()`, entre os demais componentes) e as fábricas existentes (`Envelope.novo`, `RelayConfig.fromProperties`), então a assinatura e o resultado são os mesmos de antes da mudança, e `Envelope.equals` continua comparando `payload` por conteúdo.
- Dado o módulo, então os testes existentes (`EnvelopeTest`, `RelayAlgorithmTest`, `RabbitMessagePublisherTest`, `RelayConfigTest`, `BackoffPolicyTest`, `OutboxRetentionTest`, `RelayObservabilityTest`, `RelayMemoryBoundTest`, `PackageBoundaryTest`, `ComposeImagesTest`, `OutboxMetricsTest`) e os `*IT` mantêm as mesmas asserções e passam.
- Dado o `outbox.relay.*` e o formato da mensagem publicada, então as chaves, os valores padrão, os headers e properties AMQP, as consultas SQL e o esquema são idênticos aos de antes da mudança (o texto das consultas SQL não muda).
- Dado o teste de carga do relay com `-Xmx256m -XX:MaxDirectMemorySize=64m`, então o consumo de memória do relay continua sem crescer com o número de linhas processadas, no JDK 8 e nos demais.

**História 4 — Matriz de verificação:**

- Dado o procedimento documentado no `README.md` do módulo, quando é executado com cada um dos JDKs 8, 11, 17, 21 e 25, então os testes `*Test` (surefire) passam nos cinco, e os `*IT` (failsafe, com Docker em execução) passam nos cinco.
- Dado Docker ausente, então os `*IT` são ignorados em qualquer JDK, como na convenção do repositório, e os `*Test` seguem executando.
- Dado o `README.md` do módulo, então a seção "Versões fixadas" lista Java 8 como piso, a matriz de JDKs verificada e as versões de `mssql-jdbc` (`jre8`), JUnit e Logback de teste.

**História 5 — Barreira contra regressão:**

- Dado um fonte do módulo que use uma API posterior ao Java 8 (por exemplo `String.isBlank`), quando se executa `mvn -pl mensageria-outbox compile`, então o build falha.
- Dado um JAR de dependência de execução com bytecode acima de Java 8, quando se executa o build do módulo, então ele falha com mensagem que cita a dependência.

## Comportamento atual → novo

| Aspecto | Atual | Novo |
|---|---|---|
| Nível de linguagem e bytecode do módulo | Código escrito em Java 21 (registros, `switch` por expressão, blocos de texto, padrão de `instanceof`); o `pom.xml` do módulo sem pai compila em `-source 8` e falha | Java 8, com `--release 8`, bytecode 52 |
| JDKs de execução | Java 21 como versão mínima (`AGENTS.md`, decisão D1) | JDK 8, 11, 17, 21 e 25, verificados pela suíte |
| Tipos de dados do módulo | Registros Java | Classes finais com os mesmos nomes de acesso, `equals`, `hashCode` e `toString` |
| Driver SQL Server | `mssql-jdbc` 13.4.0.jre11 | `mssql-jdbc` 13.4.0.jre8 |
| Testes | JUnit 6.0.3 e Logback 1.5.38 | JUnit 5.14.x e Logback 1.3.x |
| Demais módulos da PoC | Java 21 | Java 21 (inalterado) |

Permanece igual: as regras R1 a R27 da skill, o contrato da mensagem publicada, o esquema SQL, os parâmetros `outbox.relay.*`, as métricas, as versões de `amqp-client`, `micrometer-core`, `slf4j-api` e Testcontainers e as imagens Docker.

## Dependências entre domínios

- **Pagamentos** (futuro) — consome o módulo por adaptador Spring Boot 3.x em JDK 17 ou superior; o artefato com bytecode Java 8 funciona nessas plataformas.
- **`mensageria-inbox`** — independente deste módulo e sem relação de código com ele; a versão mínima de Java do inbox não é alterada aqui.

## Riscos e observações

- A decisão D1 de `discovery-answers.md` e `AGENTS.md` declaram "Java 21 como versão mínima" para a PoC; para o `mensageria-outbox` o piso passa a ser Java 8, com os demais módulos em Java 21. Os textos que citam Java 21 como plataforma do módulo (`AGENTS.md`, `functional-map.md`, `discovery-answers.md`, `references/technical-dependencies.md` da skill, `mensageria-outbox/README.md` e a spec `001`) precisam ser revisados na implementação; o `plan.md` registra o impacto na documentação autoritativa.
- O enforcer da raiz exige JDK 21 ou superior para o build e `--release 8` exige JDK 9 ou superior no compilador; o build do módulo roda em JDK 21 e os JDKs 8, 11, 17 e 25 entram como JDK de execução dos testes. O mecanismo (toolchains do Maven, `jvm` do surefire e do failsafe, ou matriz de CI) é decisão técnica do `plan.md`.
- Ambiente desta investigação: JDKs 8, 11, 21 e 25 instalados; o 17 não está instalado e o Docker não está disponível, de modo que a verificação dos `*IT` e do JDK 17 depende de outro ambiente.
- Testcontainers 2.0.5 tem bytecode Java 8, mas o suporte do cliente Docker a cada JDK da matriz (em especial 8 e 25) só se confirma executando a suíte; uma falha restrita a um JDK é defeito desta unidade.
- O `pom.xml` do módulo diverge do README em `mssql-jdbc` (13.4.0.jre11 no `pom.xml`, 12.10.2.jre11 no README e no `pom.xml` raiz); a escolha única da variante `jre8` é feita no `plan.md`.
- O workflow `.github/workflows/gradle.yml` é herdado de template e não serve de base para a matriz de JDKs.
