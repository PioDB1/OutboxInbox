# Pesquisa: Mensageria Outbox — compatibilidade com Java 8

Contexto, alternativas e evidências das decisões do [`plan.md`](./plan.md). Ambiente da investigação: Maven 3.9.9 sobre JDK 21 (Corretto 21.0.6), JDK 25.0.4.1 e JDK 8 instalados; sem Docker e sem JDK 17.

## Nível de linguagem e forma de compilar

**Contexto.** O módulo precisa gerar bytecode Java 8 e usar apenas a API do Java 8; o build do repositório roda em JDK 21 (enforcer da raiz).

**Alternativas:**

- **`--release 8` (propriedade `maven.compiler.release`)** — o compilador usa o formato de classe 52 e a assinatura da API do Java 8, de modo que uma chamada a método posterior ao Java 8 não compila.
- **`-source 8 -target 8`** — gera bytecode 52 mas compila contra a API do JDK que executa o build, e deixa passar `String.isBlank`, `Map.of` e similares.
- **Compilar com JDK 8** — exige o JDK 8 no build e não é compatível com o enforcer da raiz (JDK 21 ou superior).

**Decisão.** `--release 8`, definido pela propriedade `maven.compiler.release` = 8 no `pom.xml` do módulo, com `-Xlint:-options` para suprimir o aviso de opção obsoleta.

**Evidência.**

- `pom.xml` da raiz: `pluginManagement` do `maven-compiler-plugin` 3.15.0 com `<release>${maven.compiler.release}</release>`; a propriedade definida no módulo prevalece na herança.
- `javac --release 8` em JDK 21.0.6 e em JDK 25.0.4.1 compilou uma classe de teste com major 52 e as duas JVMs, junto do JDK 8, a executaram. Ambos os compiladores avisam `source value 8 is obsolete and will be removed in a future release`.
- `mvn -o -pl mensageria-outbox compile` no estado atual falha com `records are not supported in -source 8`, `switch expressions are not supported in -source 8`, `text blocks are not supported in -source 8` e `pattern matching in instanceof is not supported in -source 8` em 11 fontes; as chamadas de API posteriores ao Java 8 só aparecem depois que a sintaxe compila.

**Consequências.** O aviso do compilador indica que um JDK futuro deixará de aceitar `--release 8`; nesse dia o build do módulo passa a rodar em um JDK que ainda o aceite, o que não afeta o artefato nem os JDKs de execução. A compilação com `--release 8` é a prova do critério "nenhuma API posterior ao Java 8".

## Pai do módulo e propriedades

**Contexto.** O `pom.xml` do módulo não declara `<parent>`: não herda `maven.compiler.release`, `pluginManagement` nem `dependencyManagement` da raiz, e repete as versões de forma literal (JUnit 6.0.3, `mssql-jdbc` 13.4.0.jre11, Logback 1.5.38).

**Decisão.** O módulo declara a raiz (`br.com.db1.poc:outbox-inbox-poc:0.1.0-SNAPSHOT`) como pai, remove as versões literais e usa as gerenciadas pela raiz, e sobrescreve em `<properties>` apenas `maven.compiler.release` (8), `mssql-jdbc.version` (13.4.0.jre8) e `logback.version` (1.3.16). A raiz já gerencia `junit-bom` 5.14.4, `testcontainers-bom` 2.0.5, `amqp-client` 5.37.0, `micrometer-core` 1.17.1 e `slf4j-api` 2.0.20 pelas propriedades `junit.version` e afins.

**Evidência.** `pom.xml` da raiz, `<dependencyManagement>` (imports de `junit-bom` e `testcontainers-bom`, versões por propriedade); o Maven interpola a propriedade no contexto do módulo filho, de modo que a sobrescrita vale para a dependência gerenciada.

**Alternativas descartadas.** Alterar `mssql-jdbc.version` e `logback.version` na raiz: afetaria os módulos futuros em Java 21, que podem usar a variante `jre11` e o Logback 1.5.

**Consequências.** `mssql-jdbc` fica em escopo `runtime` no módulo: a aplicação hospedeira escolhe o driver do seu próprio classpath; no JDK 8 ela precisa da variante `jre8` (a `jre11` tem bytecode 55).

## Enforcer da raiz

**Contexto.** O `requireJavaVersion` da raiz usa `[${maven.compiler.release},)`. Com o módulo em `maven.compiler.release` 8, a regra passaria a aceitar JDK 8 para o build, e o `--release` do `javac` não existe no JDK 8.

**Decisão.** A raiz ganha a propriedade `maven.build.jdk.min` = 21, usada pelo `requireJavaVersion` e pela mensagem; `maven.compiler.release` permanece 21 na raiz para os módulos futuros.

**Evidência.** `pom.xml` da raiz, execução `enforce-versions`; a regra de rejeitar JDK anterior ao 21 é a verificada pelo caso TC-58 da unidade 001, que continua válido.

## Registros, `switch`, blocos de texto e APIs

**Contexto.** A sintaxe e as APIs posteriores ao Java 8 estão em 13 fontes de `src/main` e em 26 arquivos de `src/test` (levantamento por `grep` e pela falha de compilação).

**Decisão.**

- **Registros:** classes finais com campos `private final`, construtor com as mesmas validações do construtor compacto, acessores com o nome do componente, `equals`, `hashCode` e `toString` com a semântica do registro (comparação por `Objects.equals` por componente e `toString` no formato `Nome[componente=valor, ...]`); `Envelope` mantém o `equals`, o `hashCode` e o `toString` já escritos à mão. Registros aninhados (`OutboxDialect.PendingSummary`, `BackoffPolicy.State`) viram classes aninhadas finais.
- **`switch` por expressão sobre enum:** `switch` de instrução com `return` em cada `case` e `default` que lança `IllegalStateException`; `RelayConfig.duration` usa o `switch` por `String` clássico.
- **Blocos de texto SQL:** concatenação de literais com `\n` entre as linhas, sem `\n` final, preservando a indentação relativa que o bloco de texto produz.
- **`String.isBlank` e `String.strip`:** classe pública `br.com.db1.poc.outbox.internal.Texts` com `isBlank` e `strip` que usam `Character.isWhitespace`, a mesma definição de espaço em branco do Java 11, para que `RelayConfig` e `OutboxSchemaInstaller` mantenham o comportamento com espaços não ASCII.
- **`InputStream.readAllBytes`:** laço de leitura com buffer em `OutboxSchemaInstaller`.
- **`Map.of`:** `Collections.singletonMap` em `RabbitTopology`.
- **Testes:** `Path.of` → `Paths.get`; `List.of`/`Set.of` → `Arrays.asList`, `Collections.emptyList` e `new HashSet<>(Arrays.asList(...))`; `Stream.toList` → `collect(Collectors.toList())`; `var` → tipo explícito; `String.repeat` e `formatted` → método auxiliar local e `String.format`; `Files.readString` → `new String(Files.readAllBytes(...), UTF_8)`.

**Evidência.** Leitura de `Envelope`, `PublishableMessage`, `OutboxRow`, `PendingStats`, `RelayConfig`, `BackoffPolicy`, `PendingQuery`, `RelayFactory`, `OutboxRetention`, `OutboxSchemaInstaller` e `RabbitTopology`, e `grep` das construções em `src/main` e `src/test`; os blocos de texto de `SqlServerOutboxDialect` terminam o delimitador na linha do último trecho, sem `\n` final.

**Consequências.** A lista é completada pela compilação com `--release 8`: o que o `grep` não alcança (chamada de método posterior ao Java 8) falha a compilação e é tratado na mesma tarefa. A igualdade do texto SQL é conferida comparando a saída de `javap -p -constants` de `SqlServerOutboxDialect` antes (compilado com `-Dmaven.compiler.release=21`) e depois da conversão.

## Versões das dependências

**Contexto.** Rodar a suíte no JDK 8 exige que todo JAR do classpath de teste tenha bytecode de até Java 8.

**Evidência.** Todas as classes de cada JAR (fora de `META-INF/versions`) lidas com um leitor de cabeçalho de classe:

| Artefato | Versão | Maior bytecode |
|---|---|---|
| `amqp-client` | 5.37.0 | Java 8 |
| `micrometer-core`, `micrometer-commons`, `micrometer-observation`, `HdrHistogram`, `jspecify` | 1.17.1, 1.17.1, 1.17.1, 2.2.2, 1.0.1 | Java 8 |
| `slf4j-api` | 2.0.20 | Java 8 |
| `testcontainers` e módulos `mssqlserver`, `jdbc`, `database-commons`, `rabbitmq`, `toxiproxy` | 2.0.5 | Java 8 |
| `docker-java-*`, `jna`, `toxiproxy-java`, `commons-*`, `duct-tape`, `gson`, `jackson-annotations` | 3.7.1, 5.18.1, 2.1.11, 1.28.0 e outras, 1.0.8, 2.13.1, 2.20 | Java 8 |
| `junit-jupiter-api`, `-params`, `-engine` | 5.14.4 | Java 8 |
| `junit-platform-engine`, `-launcher` | 1.14.4 | Java 8 |
| `logback-classic`, `logback-core` | 1.3.16 | Java 8 |
| `mssql-jdbc` | 13.4.0.jre8 | Java 8 |
| `netty-common` (transitiva de `amqp-client`) | 4.2.18.Final | Java 11 em `io/netty/util/internal/shaded/org/jctools/queues/varhandle/**` (51 classes); Java 8 no restante |
| `junit-jupiter` (6.0.3), `logback-classic` (1.5.38), `mssql-jdbc` (13.4.0.jre11) | atuais | Java 17, Java 11 e Java 11 |

`mssql-jdbc` 13.4.0.jre8 e `logback-classic` 1.3.16 existem no Maven Central (`maven-metadata.xml`); o `logback-parent` 1.3.16 declara `jdk.version` 8 e compila contra `slf4j-api` 2.0.7, série à qual pertence o `slf4j-api` 2.0.20 já gerenciado. O `LogCapture` dos testes usa `Logger`, `Level`, `ILoggingEvent` e `ListAppender`, presentes nas duas linhas do Logback, e o `logback-test.xml` usa só `ConsoleAppender` e `%X{...}`.

**Decisão.** `mssql-jdbc` 13.4.0.jre8, JUnit 5.14.4 (já gerenciado pela raiz) e Logback 1.3.16; as demais versões permanecem.

**Evidência sobre o netty.** O `pom.xml` do `amqp-client` 5.37.0 declara `netty-transport`, `netty-codec` e `netty-handler` sem `<optional>`, por isso entram no classpath de compilação. Em `PlatformDependent` (netty-common 4.2.18), `hasVarHandle()` devolve `javaVersion() >= 11`, e só então as filas `*VarHandle*` são usadas; no JDK 8 essas classes não são carregadas.

**Consequências.** A linha 6.x do JUnit e a 1.5.x do Logback ficam fora do módulo enquanto o piso for Java 8.

## Guarda de bytecode das dependências

**Contexto.** Impedir que uma atualização de dependência traga bytecode acima do Java 8 sem que o build avise.

**Alternativas:**

- **Regra `enforceBytecodeVersion` do `extra-enforcer-rules` 1.12.1** — falha o build citando o artefato; atua no `maven-enforcer-plugin` já usado pela raiz.
- **Teste que lê os JARs de `java.class.path`** — código próprio que repete o que a regra já faz.

**Decisão.** A regra `enforceBytecodeVersion` com `maxJdkVersion` 8, em uma execução `enforce-bytecode` na fase `validate` do módulo, com `ignoreClasses` para `io.netty.util.internal.shaded.org.jctools.queues.varhandle.*`.

**Evidência.** Fonte `EnforceBytecodeVersion.java` do `extra-enforcer-rules` 1.12.1 (JAR de 1.12.1, bytecode Java 8, mesma versão 3.6.3 do enforcer da raiz como `provided`): percorre todas as dependências resolvidas (sem filtro de escopo por padrão), ignora `module-info` abaixo do Java 9 e as classes em `META-INF/versions/` quando `strict` é falso, e lança `Found Banned Dependency: <artefato>` com a mensagem `Restricted to JDK 8 yet <artefato> contains <classe> targeted to JDK <n>`. O padrão de `ignoreClasses` troca `.` por `/` e aceita `*`. A leitura de bytecode dos JARs da tabela acima mostra que, com `ignoreClasses`, o classpath de teste planejado passa na regra e o atual (`junit-jupiter` 6.0.3, `logback` 1.5.38, `mssql-jdbc` jre11) falha.

**Consequências.** A regra cobre os escopos `compile`, `runtime` e `test`, o que atende ao critério de execução e vale para o classpath que a suíte usa no JDK 8.

## Execução da suíte em cada JDK

**Contexto.** A suíte (`*Test` e `*IT`) precisa rodar nos JDKs 8, 11, 17, 21 e 25; o build roda em JDK 21 e o 17 não está instalado nesta estação.

**Alternativas:**

- **Propriedade `jvm` do surefire e do failsafe** — o Maven roda no JDK do build e o fork de teste usa o `java` indicado.
- **Maven Toolchains (`toolchains.xml` e `maven-toolchains-plugin`)** — exige configuração por estação e um plugin a mais.
- **Rodar o Maven inteiro em cada JDK** — o enforcer da raiz exige JDK 21 ou superior e o `--release` não existe no JDK 8.

**Decisão.** `-Djvm=<JDK_HOME>/bin/java` na linha de comando, um ciclo `mvn -pl mensageria-outbox verify` por JDK, com o procedimento no `README.md` do módulo.

**Evidência.** `plugin.xml` do `maven-surefire-plugin` 3.6.0: parâmetro `jvm`, "jvm (or path to the java executable) to use with the forking options", propriedade `${jvm}`; `plugin.xml` do `maven-failsafe-plugin` 3.6.0 declara `<jvm>${jvm}</jvm>`. Bytecode lido de `surefire-booter`, `surefire-api`, `surefire-extensions-spi`, `surefire-shared-utils` e `surefire-junit-platform` 3.6.0: Java 8, executáveis no fork em JDK 8. As duas execuções (surefire e failsafe) compartilham a propriedade, de modo que um único argumento cobre `*Test` e `*IT`; o `argLine` do failsafe (`-Xmx256m -XX:MaxDirectMemorySize=64m`) usa flags aceitas pelo JDK 8.

**Consequências.** Os `*IT` exigem Docker em execução e são ignorados sem ele; a verificação deles em cada JDK depende de um ambiente com Docker.

## Testes de guarda no código

**Decisão.** `PackageBoundaryTest` passa a afirmar também que nenhum fonte de `src/main` importa `sun.*`, `com.sun.*` ou `jdk.internal.*` nem chama `setAccessible`; um `BytecodeLevelTest` novo percorre `target/classes` e `target/test-classes` e afirma que todo arquivo `.class` tem versão principal 52.

**Evidência.** `PackageBoundaryTest` já percorre `src/main/java` por `Files.walk` e lê os imports linha a linha; os dois critérios da spec (história 1, bytecode 52; história 2, nenhum acesso a internos) não têm teste hoje.

**Consequências.** Os dois testes são `*Test` (sem Docker) e rodam nos cinco JDKs, junto da matriz.
