# mensageria-outbox

Lado produtor da entrega atômica de mensagens do padrão outbox/inbox: envelope padrão, inserção na outbox na transação local da aplicação, relay com duas estratégias atrás de uma única interface, lease de relay único, publicação no RabbitMQ com publisher confirms, retry com backoff, retenção da outbox e métricas de publicação.

É uma biblioteca com piso Java 8 (bytecode 52 e API do Java 8), que executa do JDK 8 ao JDK 25, sem Spring Boot nem executável próprio. A aplicação hospedeira fornece o `DataSource` (SQL Server), o `MessagePublisher` e o `MeterRegistry`, e controla o ciclo de vida do relay. Contexto e decisões: `.agents/specs/001-mensageria-outbox-implementacao-modulo/` e a skill `.agents/skills/mensageria-outbox/SKILL.md`; defeitos conhecidos, registrados sem correção: `.agents/context/pendencias.md`.

## Artefatos

| Artefato | Pasta | Papel |
|---|---|---|
| `br.com.db1.poc:mensageria-outbox:0.3.0-SNAPSHOT` (empacotamento `pom`) | `./` | agrega o módulo `core` e é o POM pai do `core`, do `mensageria-outbox-spring-boot3` e do `mensageria-outbox-spring-boot4`; fixa `com.rabbitmq:amqp-client` 5.37.0 no `dependencyManagement`, que prevalece sobre o BOM do Spring Boot importado pelos adaptadores, e configura compilador, enforcer de bytecode e failsafe |
| `br.com.db1.poc:mensageria-outbox-core:0.3.0-SNAPSHOT` | `core/` | a biblioteca de que a aplicação hospedeira depende; código, testes e recursos em `core/src/` |

O `pom.xml` da raiz do repositório só agrega este POM e os dois adaptadores; ele não é POM pai de nenhum deles.

## Uso pelo hospedeiro

```java
// 1. Esquema (idempotente): outbox, outbox_lease e outbox_read_point.
OutboxSchemaInstaller.install(dataSource);

// 2. Topologia no broker, declarada pela aplicação.
RabbitTopology.declareExchange(channel, "mensageria.outbox");
RabbitTopology.declareQuorumQueue(channel, "extrato.pagamento-recebido", "mensageria.outbox", "PagamentoRecebido");

// 3. Gravação na mesma transação local do dado de negócio (conexão com autoCommit=false).
OutboxWriter writer = new OutboxWriter(new SqlServerOutboxDialect());
long seq = writer.insert(connection, Envelope.novo(correlationId, idempotencyKey, aggregateKey,
        "PagamentoRecebido", (short) 1, occurredAt, payloadBytes, traceId));

// 4. Relay: a configuração vem das chaves outbox.relay.* (credenciais não passam pelo módulo).
RelayConfig config = RelayConfig.fromProperties(properties);
MessagePublisher publisher = new RabbitMessagePublisher(connectionFactory, config.exchange(), config.confirmTimeout());
Relay relay = RelayFactory.create(config, dataSource, publisher, meterRegistry, "pagamentos-1");
relay.start();   // sem efeito com outbox.relay.enabled=false
// ...
relay.close();   // para a thread e libera o lease
```

- `OutboxWriter.insert` usa a transação aberta pela aplicação e lança `IllegalStateException` com a conexão em `autoCommit`; commit, rollback e fechamento da conexão são da aplicação.
- `holderId` identifica a instância no lease e é distinto entre as instâncias que compartilham o banco.
- `ConnectionFactory` é configurado pelo hospedeiro, com a recuperação automática de conexão do cliente ligada; usuário, senha e hosts chegam por variáveis de ambiente ou propriedades externas.
- `PendingQuery.create(dialect).pending(dataSource, strategy)` devolve a contagem e a idade das linhas pendentes, o que o reconciliador usa para verificar que nada fica sem publicação com o broker disponível.
- Cada `MeterRegistry` atende a um relay.

### Formato da mensagem

Corpo AMQP = bytes do payload; campos do envelope em headers e properties (`messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt`, `traceId`). Exchange `direct` com routing key = `type`, publicação com `mandatory=true`. Detalhes em `.agents/specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md`.

### Estratégias de relay

| `outbox.relay.strategy` | Mecanismo | Linha enviada |
|---|---|---|
| `claim` | `SELECT ... WITH (UPDLOCK, READPAST, ROWLOCK)` por `seq`, `sent_at` marcado após o confirm | `sent_at IS NOT NULL` |
| `read-point` | leitura por faixa de `rv` até `MIN_ACTIVE_ROWVERSION()`, ponto de leitura persistido | `rv <= last_rv` |

Ambas publicam em ordem de `seq` por `aggregateKey`, são at-least-once e reiniciam a qualquer momento: todo o estado de retomada vive no banco. A comparação das duas está em `docs/adr/0001-estrategia-de-relay.md`.

## Parâmetros `outbox.relay.*`

| Chave | Padrão | Descrição |
|---|---|---|
| `enabled` | `false` | liga o relay na instância; só deve ser `true` em banco primário/ativo |
| `strategy` | sem padrão | `claim` ou `read-point`; ausente ou inválida impede a inicialização |
| `exchange` | `mensageria.outbox` | exchange de publicação |
| `batch-size` | `100` | linhas por ciclo |
| `poll-interval` | `500ms` | espera entre ciclos sem trabalho |
| `confirm-timeout` | `5s` | espera pelo confirm de cada mensagem |
| `backoff.initial` | `200ms` | primeiro intervalo de backoff de um `aggregateKey` |
| `backoff.multiplier` | `2.0` | fator de crescimento do backoff |
| `backoff.max` | `30s` | teto do backoff |
| `lease.duration` | `15s` | validade do lease a cada aquisição ou renovação |
| `lease.renew-interval` | `5s` | intervalo entre renovações; menor que `lease.duration` |
| `retention.max-age` | `7d` | idade mínima de uma linha enviada para ser removida |
| `retention.interval` | `60s` | frequência da limpeza |
| `retention.batch-size` | `1000` | linhas removidas por comando de limpeza |

Durações aceitam `ms`, `s`, `m`, `h`, `d` (por exemplo `500ms`, `7d`) ou ISO-8601.

## Métricas e logs

Métricas Micrometer, atualizadas a cada ciclo do relay detentor do lease. Nenhuma tem valor-meta.

| Métrica | Tipo | Significado |
|---|---|---|
| `outbox.lag.seconds` | gauge | idade, em segundos, da linha pendente mais antiga; `0` sem pendentes |
| `outbox.pending` | gauge | linhas pendentes |
| `outbox.publish.retries` | counter | tentativas de publicação de uma linha cujo `aggregateKey` já falhou antes |

Cada tentativa de publicação roda com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`, removidas ao fim da tentativa. O backend de log (SLF4J) é do hospedeiro, que inclui essas chaves no padrão ou no formato JSON dos logs.

## Versões fixadas

| Componente | Versão |
|---|---|
| Java (piso do módulo) | 8 (`--release 8`) |
| JDKs da matriz de verificação | 8, 11, 17, 21 e 25 |
| JDK do build | 21 (exigido pelo enforcer do agregador da raiz, no build a partir da raiz) |
| Maven | 3.9 ou superior (exigido pelo mesmo enforcer da raiz) |
| `com.rabbitmq:amqp-client` | 5.37.0 |
| `com.microsoft.sqlserver:mssql-jdbc` | 13.4.0.jre8 (runtime) |
| `io.micrometer:micrometer-core` | 1.17.1 |
| `org.slf4j:slf4j-api` | 2.0.20 |
| JUnit (teste) | 5.11.0 |
| Testcontainers | 2.0.5 |
| Logback (teste) | 1.3.16 |
| SQL Server | `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` |
| RabbitMQ | `rabbitmq:4.3.6-management` |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.10.0` |

A aplicação hospedeira escolhe o driver SQL Server do próprio classpath; no JDK 8 ela precisa da variante `jre8`, porque a `jre11` tem bytecode Java 11.

As imagens do `compose.yaml` na raiz e as da classe de teste `Images` são as mesmas; `ComposeImagesTest` confere a igualdade.

## Ambiente local e testes

```
cp .env.example .env            # ajuste as credenciais; o .env não é versionado
docker compose up -d --wait     # sqlserver, rabbitmq e toxiproxy saudáveis
mvn clean verify                # a partir da raiz: testes *Test (surefire) e *IT (failsafe)
```

Os testes `*IT` sobem os próprios contêineres com Testcontainers, com credenciais efêmeras (ou as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER` e `RABBITMQ_DEFAULT_PASS`, quando definidas), e exigem Docker em execução; sem Docker eles são ignorados. O failsafe roda com `-Xmx256m -XX:MaxDirectMemorySize=64m`, os limites sob os quais `RelayMemoryIT` verifica que o relay não cresce em memória com o volume processado.

O Toxiproxy do `compose.yaml` expõe a API de controle em `TOXIPROXY_API_PORT` e o proxy `rabbitmq` em `TOXIPROXY_RABBITMQ_PORT`; a aplicação aponta para essa porta para sofrer os toxics.

### Matriz de JDKs

O build a partir da raiz exige JDK 21 (enforcer do agregador da raiz); o compilador usa `--release 8` e o artefato `mensageria-outbox-core` tem bytecode 52. Cada JDK da matriz (8, 11, 17, 21 e 25) executa a suíte do `core` com um ciclo `verify`, a partir da raiz, informando o `java` do JDK em `-Djvm` (propriedade nativa do surefire e do failsafe):

```
mvn -pl mensageria-outbox/core verify -Djvm=<JDK_HOME>/bin/java
```

- O seletor `-pl mensageria-outbox` escolhe só o POM agregador e não executa nenhum teste; use `-pl mensageria-outbox/core` ou `-pl :mensageria-outbox-core`.
- O Maven e o compilador seguem no JDK 21; `-Djvm` troca só a JVM dos forks do surefire (`*Test`) e do failsafe (`*IT`). A propriedade `java.version` dos relatórios XML de `core/target/surefire-reports` e `core/target/failsafe-reports` registra o JDK que executou os testes.
- Os `*Test` rodam sem Docker. Os `*IT` exigem Docker em execução e são ignorados sem ele; uma execução sem Docker não verifica o relay contra SQL Server e RabbitMQ.
- Com `mvn -pl mensageria-outbox/core test -Djvm=<JDK_HOME>/bin/java` só os `*Test` executam.

Barreiras contra a volta de recursos posteriores ao Java 8: a compilação com `--release 8` recusa sintaxe e API novas; a regra `enforceBytecodeVersion` (execução `enforce-bytecode`, fase `validate`) recusa dependência com bytecode acima do Java 8 e cita o artefato; `BytecodeLevelTest` confere a versão principal 52 de todo `.class` de `core/target/classes` e `core/target/test-classes`; `PackageBoundaryTest` recusa imports `sun.*`, `com.sun.*` e `jdk.internal.*` e chamadas a `setAccessible` em `core/src/main`.
