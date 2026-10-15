# Casos de teste: Mensageria Outbox — compatibilidade com Java 8

Catálogo derivado de [`spec.md`](./spec.md), [`plan.md`](./plan.md) e [`research.md`](./research.md). O módulo é uma biblioteca sem endpoint; os casos observam o build, os arquivos `.class` e JARs, a execução da suíte em cada JDK e a API Java do módulo. Cada caso é o ponto de partida de um teste automatizado ou de uma verificação de build, e também um roteiro de validação manual. O comportamento do domínio não muda nesta unidade: os casos de funcionalidade (relay, outbox, lease, retenção, métricas) reaparecem apenas como a suíte existente rodando em cada JDK, sem recobrir as regras R1 a R27.

## Pré-condições

- Repositório na raiz, com o `pom.xml` raiz e o módulo `mensageria-outbox`. O build roda em **JDK 21** com Maven 3.9 ou superior.
- JDKs de execução da matriz instalados e acessíveis por `<JDK_HOME>`: 8, 11, 17, 21 e 25. A execução em um JDK é `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java`.
- Docker em execução nos casos marcados "com Docker" (testes `*IT`, com Testcontainers e credenciais efêmeras ou as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`); os casos marcados "sem Docker" usam só os `*Test`.
- Árvore de trabalho limpa nos casos que alteram `pom.xml` ou fontes temporariamente; a alteração é revertida ao fim do caso.
- Para os casos de comparação com o estado anterior à conversão, uma cópia da revisão anterior ao primeiro commit desta unidade, compilável com `-Dmaven.compiler.release=21`.

## História 1 — Piso Java 8 (build e artefato)

### TC-1 (obrigatório) — Build completo compila com `--release 8`

1. Na raiz, com JDK 21, executar `mvn -pl mensageria-outbox -am clean verify`.
2. Verificar nos logs do `maven-compiler-plugin` o nível usado nas execuções `compile` e `testCompile`.

**Esperado:** o build termina com sucesso; `compile` e `testCompile` usam `release` 8; nenhum erro de compilação do módulo.

### TC-2 (obrigatório) — Todo `.class` do artefato tem versão principal 52

1. Gerar o JAR do módulo (`mvn -pl mensageria-outbox package`).
2. Ler o cabeçalho de cada arquivo `.class` do JAR (bytes 6 e 7 do arquivo).
3. Executar o `BytecodeLevelTest` (`*Test`, sem Docker).

**Esperado:** a versão principal é 52 (Java 8) em todos os arquivos `.class` do JAR; o `BytecodeLevelTest` passa e confirma 52 em `target/classes` e em `target/test-classes`.

### TC-3 (obrigatório) — Fontes sem sintaxe nem API posteriores ao Java 8

1. Procurar em `src/main/java` e em `src/test/java` as construções `record`, `var`, `->` em `case`, `"""`, padrão de `instanceof` (`instanceof T nome`) e `sealed`.
2. Procurar as APIs substituídas pelo plano: `isBlank`, `strip`, `readAllBytes`, `Map.of`, `List.of`, `Set.of`, `Stream.toList`, `Path.of`, `String.repeat`, `formatted`, `Files.readString`.
3. Executar `mvn -pl mensageria-outbox compile test-compile`.

**Esperado:** nenhuma ocorrência nos passos 1 e 2 (exceto `isBlank` e `strip` dentro de `internal.Texts`, que implementa as duas operações com a definição de espaço em branco do Java 11, sem chamar `String.isBlank` nem `String.strip`); a compilação com `--release 8` conclui sem erro, o que prova a ausência de API posterior ao Java 8.

### TC-4 (obrigatório) — Classpath com bytecode de no máximo Java 8 e driver `jre8`

1. Executar `mvn -pl mensageria-outbox validate` (a execução `enforce-bytecode` do enforcer).
2. Executar `mvn -pl mensageria-outbox dependency:tree`.

**Esperado:** o passo 1 termina com sucesso; no passo 2, `com.microsoft.sqlserver:mssql-jdbc` aparece na versão `13.4.0.jre8` em escopo `runtime`, `org.junit.jupiter:junit-jupiter` na linha 5.14.x e `ch.qos.logback:logback-classic` na linha 1.3.x (escopo `test`); `amqp-client` 5.37.0, `micrometer-core` 1.17.1, `slf4j-api` 2.0.20 e Testcontainers 2.0.5 mantêm as versões.

### TC-5 (obrigatório) — Inicialização do relay no JDK 8 (com Docker)

1. Executar `mvn -pl mensageria-outbox verify -Djvm=<JDK_8_HOME>/bin/java`.
2. Abrir o relatório de `RelayPublicationIT` (failsafe) e os logs da execução.

**Esperado:** o relay (`RelayFactory.create` e `Relay.start`) inicia e publica nos testes `*IT`; nenhum `UnsupportedClassVersionError`, `NoSuchMethodError` ou `NoClassDefFoundError` nos relatórios e nos logs.

### TC-6 (recomendado) — O pai da raiz fornece o enforcer e as versões gerenciadas

1. Abrir o `pom.xml` do módulo.
2. Executar `mvn -pl mensageria-outbox help:effective-pom` e consultar `maven.compiler.release`, `mssql-jdbc.version`, `logback.version` e a configuração do `requireJavaVersion`.

**Esperado:** o módulo declara o `pom.xml` raiz como pai e não repete versões de dependência; `maven.compiler.release` é 8, `mssql-jdbc.version` é 13.4.0.jre8 e `logback.version` é 1.3.16 no módulo; a raiz mantém `maven.compiler.release` 21 e `maven.build.jdk.min` 21, e o `requireJavaVersion` usa `maven.build.jdk.min`.

### TC-7 (recomendado) — Build com JDK anterior ao 21 é recusado

1. Executar `mvn -pl mensageria-outbox validate` com o Maven rodando em JDK 8 ou 11.

**Esperado:** o build falha na regra `requireJavaVersion` do enforcer, com a mensagem "O build exige Java 21 ou superior."; o JDK 21 ou superior segue aceito.

## História 2 — Versões mais novas

### TC-8 (obrigatório) — Suíte `*Test` passa nos JDKs 11, 17, 21 e 25 (sem Docker)

1. Para cada JDK de 11, 17, 21 e 25, executar `mvn -pl mensageria-outbox test -Djvm=<JDK_HOME>/bin/java`.
2. Conferir nos relatórios do surefire as falhas e os erros.

**Esperado:** todos os `*Test` passam nos quatro JDKs; nenhuma falha decorre de API removida, de acesso reflexivo a internos do JDK ou de diferença de comportamento entre versões.

### TC-9 (obrigatório) — Suíte `*IT` passa nos JDKs 11, 17, 21 e 25 (com Docker)

1. Para cada JDK de 11, 17, 21 e 25, executar `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java`.
2. Conferir nos relatórios do failsafe as falhas e os erros.

**Esperado:** todos os `*IT` passam nos quatro JDKs, com os limites `-Xmx256m -XX:MaxDirectMemorySize=64m` do failsafe.

### TC-10 (obrigatório) — Fontes sem acesso a internos do JDK

1. Executar `PackageBoundaryTest` (`*Test`, sem Docker).

**Esperado:** o teste passa e afirma que nenhum fonte de `src/main` importa `sun.*`, `com.sun.*` ou `jdk.internal.*` nem chama `setAccessible`, além das asserções de fronteira que já tinha (nenhum import de `org.springframework`; `com.rabbitmq` só no pacote `rabbit`).

### TC-11 (recomendado) — Import de interno do JDK é detectado

1. Acrescentar temporariamente a um fonte de `src/main` o import `sun.misc.Unsafe`.
2. Executar `PackageBoundaryTest`.
3. Reverter a alteração.

**Esperado:** o `PackageBoundaryTest` falha apontando o arquivo e a linha do import; com a alteração revertida, passa.

### TC-12 (recomendado) — Chamada a `setAccessible` é detectada

1. Acrescentar temporariamente a um fonte de `src/main` uma chamada `setAccessible(true)`.
2. Executar `PackageBoundaryTest`.
3. Reverter a alteração.

**Esperado:** o `PackageBoundaryTest` falha apontando o arquivo; com a alteração revertida, passa.

## História 3 — Comportamento preservado (R1 a R27)

### TC-13 (obrigatório) — `Envelope`: acessores, fábrica, cópia do payload e `equals` por conteúdo

1. Executar `EnvelopeTest` (`*Test`, sem Docker).
2. Construir dois `Envelope` com os mesmos campos e arrays de `payload` distintos de mesmo conteúdo, e um terceiro com um byte diferente.
3. Chamar `Envelope.novo(...)` duas vezes com os mesmos argumentos.
4. Alterar o array passado ao construtor e o array devolvido por `payload()`, e ler `payload()` de novo.

**Esperado:** o `EnvelopeTest` passa sem asserção removida; os dois primeiros envelopes são iguais e têm o mesmo `hashCode`, o terceiro é diferente; `Envelope.novo` gera `messageId` distintos; a alteração dos arrays não muda o payload guardado; `toString` segue o formato `Envelope[messageId=..., ..., payloadBytes=<n>, traceId=...]`.

### TC-14 (obrigatório) — `Envelope`: validações dos campos

1. Construir `Envelope` com cada campo obrigatório nulo, com `idempotencyKey`, `aggregateKey`, `type` ou `traceId` vazios, e com cada um desses textos acima do tamanho máximo (200, 100, 100 e 64 caracteres).
2. Construir `Envelope` com cada texto exatamente no tamanho máximo e com `payload` vazio.

**Esperado:** os casos do passo 1 lançam a mesma exceção e a mesma mensagem que o `EnvelopeTest` já afirma; os do passo 2 constroem o envelope.

### TC-15 (obrigatório) — Tipos convertidos mantêm acessores, validações e igualdade

1. Executar os testes que usam `PublishableMessage`, `OutboxRow`, `PendingStats`, `OutboxDialect.PendingSummary` e `BackoffPolicy` (`RelayAlgorithmTest`, `RabbitMessagePublisherTest`, `RelayObservabilityTest`, `BackoffPolicyTest`, `OutboxMetricsTest`).
2. Chamar `PublishableMessage.from(envelope)` e comparar cada acessor com o do envelope.
3. Construir `PendingStats` com `count` negativo, com `oldestAge` nulo e com `oldestAge` negativo; depois com `count` 0 e `oldestAge` `Duration.ZERO`.
4. Chamar `OutboxRow.aggregateKey()` e os acessores `seq()`, `rv()` e `envelope()`.

**Esperado:** os testes passam sem asserção removida; os acessores devolvem os valores informados; os três primeiros `PendingStats` do passo 3 lançam `IllegalArgumentException` e o último é aceito; `OutboxRow.aggregateKey()` devolve o `aggregateKey` do envelope; cada tipo convertido compara componentes com `Objects.equals` e imprime `Nome[componente=valor, ...]`.

### TC-16 (obrigatório) — `RelayConfig`: valores padrão, leitura das chaves e validações

1. Executar `RelayConfigTest` (`*Test`, sem Docker).
2. Chamar `RelayConfig.fromProperties` só com `outbox.relay.strategy` e ler todos os acessores.
3. Informar durações em `ms`, `s`, `m`, `h`, `d` e em ISO-8601, e um valor de duração inválido.
4. Omitir `outbox.relay.strategy` e informar um valor de estratégia desconhecido.
5. Informar `backoff.multiplier` menor que 1.0, `backoff.max` menor que `backoff.initial` e `lease.renew-interval` maior ou igual a `lease.duration`.

**Esperado:** o `RelayConfigTest` passa sem asserção removida; os padrões são os do plano da unidade 001 e do `README.md` do módulo (`enabled=false`, `exchange=mensageria.outbox`, `batch-size=100`, `poll-interval=500ms`, `confirm-timeout=5s`, `backoff.initial=200ms`, `backoff.multiplier=2.0`, `backoff.max=30s`, `lease.duration=15s`, `lease.renew-interval=5s`, `retention.max-age=7d`, `retention.interval=60s`, `retention.batch-size=1000`); cada duração é convertida para a unidade correspondente; os casos dos passos 3 a 5 lançam `IllegalArgumentException`, e os do passo 4 citam os valores aceitos.

### TC-17 (obrigatório) — `RelayConfig`: espaços em branco não ASCII

1. Chamar `RelayConfig.fromProperties` com `outbox.relay.exchange` contendo espaço ASCII e o caractere U+2003 no início e no fim do texto (`<U+2003> troca <U+2003>`).
2. Chamar com `outbox.relay.exchange` formado só por U+2003.
3. Chamar com `outbox.relay.strategy` precedido e seguido de espaços.

**Esperado:** no passo 1 o `exchange` é `troca` (os espaços são removidos como o `strip()` do Java 11 faz); no passo 2 o valor é tratado como ausente e o `exchange` assume `mensageria.outbox`; no passo 3 a estratégia é reconhecida.

### TC-18 (obrigatório) — Seleção por estratégia depois da conversão dos `switch`

1. Chamar `RelayFactory.create` com `outbox.relay.strategy=claim` e com `outbox.relay.strategy=read-point`.
2. Executar `PendingQuery.create(dialect).pending(dataSource, strategy)` para `CLAIM` e para `READ_POINT` sobre um dialeto de teste que registra a chamada.
3. Executar a retenção (`OutboxRetention`) para `CLAIM` e para `READ_POINT` sobre um dialeto de teste que registra a chamada (`OutboxRetentionTest`).

**Esperado:** a fábrica devolve a implementação de relay da estratégia pedida (claim: `ClaimRelay`; ponto de leitura: `ReadPointRelay`); `PendingQuery` consulta `pendingUnsent` para `CLAIM` e `pendingAheadOfReadPoint` para `READ_POINT`; a retenção chama `deleteSentByTimestamp` para `CLAIM` e `deleteBehindReadPoint` para `READ_POINT`, com as mesmas chamadas que `OutboxRetentionTest` já afirma.

### TC-19 (obrigatório) — Texto das consultas SQL idêntico ao anterior à conversão

1. Na cópia da revisão anterior à conversão, compilar com `mvn -Dmaven.compiler.release=21 -pl mensageria-outbox compile` e executar `javap -p -constants -cp target/classes br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect`, guardando a saída.
2. No módulo convertido, compilar com `--release 8` e executar o mesmo `javap`, guardando a saída.
3. Comparar as duas saídas.

**Esperado:** a diferença é vazia: todas as constantes de texto SQL (`INSERT`, `PENDING_BETWEEN`, `ADVANCE_READ_POINT`, `PENDING_UNSENT`, `PENDING_AHEAD_OF_READ_POINT`, `RENEW_LEASE`, `INSERT_LEASE`, `RELEASE_LEASE`, `DELETE_SENT`, `DELETE_BEHIND_READ_POINT` e as demais) têm o mesmo valor, inclusive quebras de linha e indentação; `schema.sql` permanece idêntico.

### TC-20 (obrigatório) — Instalação do esquema (com Docker)

1. Executar `OutboxSchemaInstallerIT`.
2. Chamar `OutboxSchemaInstaller.install(dataSource)` duas vezes sobre um banco vazio.

**Esperado:** o teste passa; as tabelas `outbox`, `outbox_lease` e `outbox_read_point` existem depois da primeira chamada e a segunda chamada não falha nem altera o esquema.

### TC-21 (obrigatório) — Topologia RabbitMQ e formato da mensagem publicada (com Docker)

1. Executar `RabbitMessagePublisherTest` (`*Test`) e `RabbitMessagePublisherIT`.
2. Declarar a exchange com `RabbitTopology.declareExchange` e a fila com `RabbitTopology.declareQuorumQueue`, duas vezes com os mesmos argumentos.
3. Publicar uma mensagem pelo `RabbitMessagePublisher` e ler da fila.

**Esperado:** os testes passam sem asserção removida; a fila é `quorum` e durável, declarada só com o argumento `x-queue-type=quorum`, e a segunda declaração não recebe `PRECONDITION_FAILED`; a mensagem tem os headers `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` e `traceId`, as properties `message_id`, `correlation_id` e `type`, `delivery_mode` 2 e corpo igual aos bytes do payload, como em `contracts/mensagem-publicada.md` da unidade 001.

### TC-22 (obrigatório) — Fluxos do domínio com as duas estratégias (com Docker)

1. Executar os `*IT` existentes (`RelayPublicationIT`, `RelayOrderingIT`, `ReadPointRelayIT`, `RelayBackoffIT`, `RelayLeaseIT`, `RelayRetentionIT`, `RelayObservabilityIT`, `RelayObservationsIT`, `OutboxWriterIT`, `MinActiveRowversionIT`) com JDK 21 e a suíte convertida.

**Esperado:** todos passam com as mesmas asserções de antes da conversão (publicação com confirm, ordem por `aggregateKey`, nenhuma linha pulada com commits fora da ordem de `seq`, backoff, lease, retenção, métricas `outbox.lag.seconds`, `outbox.pending` e `outbox.publish.retries`, logs com `messageId`, `correlationId` e `aggregateKey`).

### TC-23 (obrigatório) — Testes convertidos mantêm as asserções

1. Comparar o diff de `src/test` entre a revisão anterior e a convertida.
2. Executar `EnvelopeTest`, `RelayAlgorithmTest`, `RabbitMessagePublisherTest`, `RelayConfigTest`, `BackoffPolicyTest`, `OutboxRetentionTest`, `RelayObservabilityTest`, `RelayMemoryBoundTest`, `PackageBoundaryTest`, `ComposeImagesTest` e `OutboxMetricsTest`.

**Esperado:** o diff mostra apenas a troca de construções (`Paths.get`, `Arrays.asList`, `Collections.emptyList`, `Collectors.toList`, tipos explícitos, `String.format`, `Files.readAllBytes`, classes no lugar de registros de teste); nenhuma asserção removida ou enfraquecida (o `PackageBoundaryTest` apenas ganha as asserções de `sun.*`, `com.sun.*`, `jdk.internal.*` e `setAccessible`); os onze testes passam.

### TC-24 (obrigatório) — Memória do relay sob os limites de heap e de memória direta

1. Executar `RelayMemoryBoundTest` (`*Test`, sem Docker) e `RelayMemoryIT` (com Docker), este com `-Xmx256m -XX:MaxDirectMemorySize=64m`, com `-Djvm=<JDK_8_HOME>/bin/java` e com `-Djvm=<JDK_21_HOME>/bin/java`.

**Esperado:** o volume processado, várias vezes o tamanho do lote, termina sem `OutOfMemoryError`, e o consumo de heap medido não cresce com o número de linhas processadas, no JDK 8 e no JDK 21.

### TC-25 (recomendado) — Parâmetros `outbox.relay.*` e métricas inalterados

1. Comparar a tabela "Parâmetros `outbox.relay.*`" e a tabela "Métricas e logs" do `README.md` do módulo com as da revisão anterior.

**Esperado:** as chaves, os padrões e as descrições são idênticos; os nomes e os tipos das métricas são idênticos.

## História 4 — Matriz de verificação

### TC-26 (obrigatório) — Procedimento documentado cobre os cinco JDKs (sem Docker)

1. Seguir o procedimento do `README.md` do módulo e executar `mvn -pl mensageria-outbox test -Djvm=<JDK_HOME>/bin/java` para os JDKs 8, 11, 17, 21 e 25.

**Esperado:** os `*Test` passam nos cinco JDKs; o procedimento do README é suficiente para repetir a execução sem passos extras.

### TC-27 (obrigatório) — Suíte completa `*Test` e `*IT` nos cinco JDKs (com Docker)

1. Para cada JDK de 8, 11, 17, 21 e 25, executar `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java` com Docker em execução.
2. Conferir os relatórios do surefire e do failsafe de cada execução.

**Esperado:** `*Test` (surefire) e `*IT` (failsafe) passam nos cinco JDKs; os `*IT` rodam, sem estar ignorados.

### TC-28 (recomendado) — O fork de teste usa o JDK indicado

1. Executar `mvn -pl mensageria-outbox verify -Djvm=<JDK_8_HOME>/bin/java` com o Maven em JDK 21.
2. Ler a propriedade `java.version` registrada nos relatórios XML do surefire e do failsafe.

**Esperado:** os relatórios registram a versão do JDK 8, enquanto o Maven e o compilador seguem no JDK 21; o mesmo vale para os demais JDKs da matriz.

### TC-29 (obrigatório) — Docker ausente

1. Com o Docker parado, executar `mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java` para um JDK da matriz.

**Esperado:** os `*IT` são ignorados, como na convenção do repositório, e os `*Test` executam e passam; o build termina com sucesso.

### TC-30 (recomendado) — `README.md` do módulo descreve Java 8 e a matriz

1. Ler a descrição inicial, a seção "Versões fixadas" e o procedimento de teste do `README.md` do módulo.

**Esperado:** o README descreve o módulo como biblioteca com piso Java 8, lista a matriz de JDKs 8, 11, 17, 21 e 25 verificada, o `-Djvm` do procedimento, e as versões `mssql-jdbc` 13.4.0.jre8 (runtime), JUnit 5.14.x e Logback 1.3.x (teste), junto de `amqp-client`, `micrometer-core`, `slf4j-api` e Testcontainers inalterados.

### TC-31 (recomendado) — Documentação autoritativa reflete o piso Java 8

1. Procurar "Java 21" em `.agents/skills/mensageria-outbox/references/technical-dependencies.md`, `.agents/skills/mensageria-outbox/SKILL.md`, `AGENTS.md`, `.agents/maps/functional-map.md`, `.agents/context/discovery-answers.md`, `.agents/skills/ambiente-docker-sqlserver-rabbitmq/SKILL.md`, `.agents/skills/testes-de-falha-resiliencia/SKILL.md` e `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/api-modulo.md`.

**Esperado:** nenhum desses textos declara Java 21 como plataforma ou versão mínima do `mensageria-outbox`; cada um registra Java 8 como piso do módulo (e a matriz de JDKs, quando o texto trata de compatibilidade); `AGENTS.md` mantém Java 21 como versão mínima dos demais módulos e documenta `-Djvm`; `api-modulo.md` não declara `record`.

## História 5 — Barreira contra regressão

### TC-32 (obrigatório) — API posterior ao Java 8 quebra o build

1. Acrescentar temporariamente a um fonte de `src/main` a expressão `"x".isBlank()`.
2. Executar `mvn -pl mensageria-outbox compile`.
3. Reverter a alteração.

**Esperado:** o build falha na compilação, com erro de símbolo não encontrado para `isBlank`; com a alteração revertida, compila.

### TC-33 (obrigatório) — Dependência com bytecode acima do Java 8 quebra o build

1. Alterar temporariamente `mssql-jdbc.version` do módulo para `13.4.0.jre11` (ou `logback.version` para 1.5.38).
2. Executar `mvn -pl mensageria-outbox validate`.
3. Reverter a alteração.

**Esperado:** o build falha na execução `enforce-bytecode` do enforcer, com mensagem que cita a dependência (`Found Banned Dependency: ...mssql-jdbc...` com "Restricted to JDK 8"); com a alteração revertida, `validate` passa.

### TC-34 (obrigatório) — As classes do netty exigidas só no JDK 11 ou superior não bloqueiam o build

1. Executar `mvn -pl mensageria-outbox validate` com o classpath planejado (`amqp-client` 5.37.0 e o `netty-common` 4.2.18.Final transitivo).
2. Executar `RabbitMessagePublisherIT` com `-Djvm=<JDK_8_HOME>/bin/java` (com Docker).

**Esperado:** o `validate` passa, porque a regra ignora `io.netty.util.internal.shaded.org.jctools.queues.varhandle.*`; o `RabbitMessagePublisherIT` passa no JDK 8, sem `UnsupportedClassVersionError` ou `NoClassDefFoundError`.

### TC-35 (recomendado) — `BytecodeLevelTest` detecta bytecode acima de 52

1. Alterar temporariamente `maven.compiler.release` do módulo para 11.
2. Executar `mvn -pl mensageria-outbox test`.
3. Reverter a alteração.

**Esperado:** o `BytecodeLevelTest` falha apontando arquivos `.class` com versão acima de 52; com a alteração revertida, passa.
