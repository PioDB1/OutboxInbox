# mensageria-outbox

Entrega atômica de mensagens do padrão outbox/inbox. Lado produtor: envelope padrão, inserção na outbox na transação local da aplicação, relay com duas estratégias atrás de uma única interface, lease de relay único, publicação no RabbitMQ com publisher confirms, retry com backoff, retenção da outbox e métricas de publicação. Lado consumidor: consumo com ack manual, inbox e dedup por `messageId` na transação local, serialização por `aggregateKey`, retry com backoff, DLQ, métricas e logs de consumo.

É uma biblioteca com piso Java 8 (bytecode 52 e API do Java 8), que executa do JDK 8 ao JDK 25, sem Spring Boot nem executável próprio. A aplicação hospedeira fornece o `DataSource` (SQL Server na outbox; SQL Server ou H2 na inbox), o `MessagePublisher` ou o `ConnectionFactory` do consumo, o `MeterRegistry`, e controla o ciclo de vida do relay e do consumidor. Contexto e decisões: `.agents/specs/001-mensageria-outbox-implementacao-modulo/` (outbox), `.agents/specs/003-mensageria-outbox-nucleo-inbox/` (consumidor) e as skills `.agents/skills/mensageria-outbox/SKILL.md` e `.agents/skills/mensageria-inbox/SKILL.md`; defeitos conhecidos e limites registrados sem correção: `.agents/context/pendencias.md`.

## Artefatos

| Artefato | Pasta | Papel |
|---|---|---|
| `br.com.db1.poc:mensageria-outbox:0.4.0-SNAPSHOT` (empacotamento `pom`) | `./` | agrega o módulo `core` e é o POM pai do `core`, do `mensageria-outbox-spring-boot3` e do `mensageria-outbox-spring-boot4`; fixa `com.rabbitmq:amqp-client` 5.37.0 no `dependencyManagement`, que prevalece sobre o BOM do Spring Boot importado pelos adaptadores, e configura compilador, enforcer de bytecode e failsafe |
| `br.com.db1.poc:mensageria-outbox-core:0.4.0-SNAPSHOT` | `core/` | a biblioteca de que a aplicação hospedeira depende, com a outbox e a inbox; código, testes e recursos em `core/src/` |

O `pom.xml` da raiz do repositório só agrega este POM e os dois adaptadores; ele não é POM pai de nenhum deles.

## Pacotes do `core`

| Pacote (`br.com.db1.poc.`) | Conteúdo |
|---|---|
| `outbox.write` | `OutboxWriter`: inserção na outbox na transação da aplicação |
| `outbox.dialect`, `outbox.dialect.sqlserver` | `OutboxDialect` e o `SqlServerOutboxDialect` |
| `outbox.publish` | `MessagePublisher`, `PublishableMessage` e `PublishException` |
| `outbox.rabbit` | `RabbitMessagePublisher`: publicação com confirms |
| `outbox.relay` | `Relay`, `RelayFactory`, `RelayConfig` e as duas estratégias |
| `outbox.lease`, `outbox.retention`, `outbox.metrics` | lease de relay único, retenção e métricas |
| `outbox.schema` | `OutboxSchemaInstaller` |
| `inbox` | `InboxConsumer`, `InboxConfig`, `InboxMessageHandler`, `InboxTransactionExecutor`, `JdbcInboxTransactionExecutor`, `EnvelopeReader`, `MessageSource`, `Delivery`, `BlockedKeys`, `InMemoryBlockedKeys` e `RetryBackoff` |
| `inbox.dialect`, `inbox.dialect.sqlserver`, `inbox.dialect.h2` | `InboxDialect`, `SqlServerInboxDialect` e `H2InboxDialect` |
| `inbox.rabbit` | `RabbitMessageSource`: consumo com ack manual e publicação na DLQ com confirm |
| `inbox.schema`, `inbox.metrics` | `InboxSchemaInstaller` e `InboxMetrics` |
| `shared.envelope` | `Envelope` |
| `shared.rabbit` | `RabbitTopology` |
| `shared.config`, `shared.logging`, `shared.schema`, `shared.dialect`, `shared.text` | `PropertyReader`, `MessageMdc`, `SqlScripts`, `SqlServerErrors` e `Texts` |

`shared` é o código comum à outbox e à inbox, no mesmo artefato `mensageria-outbox-core`. Os pacotes `outbox` e `inbox` dependem de `shared`; `shared` não depende de nenhum dos dois, e `outbox` e `inbox` não dependem uma da outra. O `PackageBoundaryTest` verifica essa direção.

## Uso pelo hospedeiro (outbox)

```java
import br.com.db1.poc.shared.envelope.Envelope;
import br.com.db1.poc.shared.rabbit.RabbitTopology;

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

## Consumidor (inbox)

### Uso pelo hospedeiro

```java
import br.com.db1.poc.shared.rabbit.RabbitTopology;

// 1. Tabela inbox no banco da aplicação consumidora (idempotente), com o dialeto do banco.
InboxDialect dialect = new H2InboxDialect();   // ou new SqlServerInboxDialect()
InboxSchemaInstaller.install(dataSource, dialect);

// 2. Filas no broker, declaradas pela aplicação: a principal ligada ao exchange e a DLQ sem ligação.
RabbitTopology.declareQuorumQueue(channel, "extrato.pagamento-recebido", "mensageria.outbox", "PagamentoRecebido");
RabbitTopology.declareQuorumQueue(channel, "extrato.pagamento-recebido.dlq");

// 3. Tratador: o efeito de negócio, na conexão da transação em que o messageId é gravado na inbox.
InboxMessageHandler handler = (envelope, connection) -> {
    // grava o lançamento pela connection; o payload é opaco para a biblioteca
};

// 4. Consumidor: a configuração vem das chaves inbox.consumer.*.
InboxConfig config = InboxConfig.fromProperties(properties);
InboxConsumer consumer = new InboxConsumer(config,
        new RabbitMessageSource(connectionFactory, config.confirmTimeout()), dialect,
        new JdbcInboxTransactionExecutor(dataSource), handler, new InMemoryBlockedKeys(),
        new InboxMetrics(meterRegistry));
consumer.start();   // confirma as duas filas e começa a receber
// ...
consumer.close();   // cancela o consumo, deixa terminar as tentativas em andamento e fecha a conexão
```

- **Tabela e script.** A tabela `inbox` (`message_id` chave primária e `consumed_at` obrigatório) é criada pelo script do dialeto, executado antes de a aplicação subir: `core/src/main/resources/br/com/db1/poc/inbox/schema/sqlserver/schema.sql` (`UNIQUEIDENTIFIER` e `DATETIME2`, com a mesma estrutura do `sql/outbox.sql` do `demooutboxinbox`) ou `.../h2/schema.sql` (`UUID` e `TIMESTAMP`). `InboxSchemaInstaller.install(dataSource, dialect)` executa o script e pode ser repetido. O dialeto H2 grava `consumed_at` com o instante UTC, qualquer que seja a zona da JVM; a aplicação hospedeira traz o driver do banco.
- **Fila e DLQ.** A aplicação declara a fila principal (quorum, durável) e a DLQ (quorum, durável, sem ligação a exchange) com os argumentos de `RabbitTopology`. Ao iniciar, o consumidor confirma as duas com `queueDeclarePassive` e falha, com mensagem que nomeia a fila, se alguma não existir. O `ConnectionFactory` é o da aplicação, com a recuperação automática de conexão ligada.
- **Tratador.** `InboxMessageHandler.handle(envelope, connection)` recebe o `Envelope` lido dos headers (`messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt`, `traceId`) e o `payload` igual, byte a byte, ao corpo da mensagem; a validação do conteúdo é da aplicação. O tratador não faz commit nem rollback e não fecha a conexão; uma exceção desfaz a transação.
- **Transação.** Em cada tentativa, o `InboxTransactionExecutor` abre uma transação local, o consumidor consulta o `messageId` na inbox, grava-o e chama o tratador; o ack só sai depois do commit. A conexão em autocommit é recusada com `IllegalStateException`. `JdbcInboxTransactionExecutor` serve ao uso sem framework.
- **Duplicata.** Um `messageId` já presente na inbox, ou a violação da chave primária da inbox em duas entregas simultâneas, descarta a mensagem com ack, sem chamar o tratador. Uma violação de unicidade lançada pelo tratador é falha de processamento.
- **Ordem.** Uma mensagem por `aggregateKey` por vez, na ordem de entrega do broker, com chaves diferentes em paralelo nas `workers` threads de trabalho. Uma mensagem sem `aggregateKey` legível é serializada sob a chave reservada `<sem-aggregateKey>`.
- **Retry e DLQ.** Uma mensagem cujo processamento falha é repetida na memória depois de `min(backoff.max, backoff.initial × backoff.multiplier^(n-1))`, com a chave em espera e as outras chaves em andamento. Ao exceder `max-attempts` (a primeira tentativa conta), a mensagem é publicada na DLQ com confirm positivo, a original recebe ack e a chave passa a bloqueada: as mensagens seguintes dela, já aguardando ou novas, vão para a DLQ sem chamar o tratador. A mensagem na DLQ preserva corpo e headers e acrescenta `x-inbox-failure` (`max-attempts` ou `blocked-key`) e `x-inbox-attempts` (`0` para `blocked-key`). Uma publicação na DLQ que falha mantém a original sem ack e se repete pelos intervalos do backoff, sem contar como tentativa de processamento.
- **Ack que falha.** Um ack que falha depois do commit é registrado em log e não conta como falha de processamento; o broker reentrega a mensagem e a inbox a descarta. Uma entrega de um canal perdido (`StaleDeliveryException`) é abandonada sem repetir a operação.

### Parâmetros `inbox.consumer.*`

| Chave | Padrão | Descrição |
|---|---|---|
| `queue` | sem padrão, obrigatória | fila principal (quorum, durável, declarada pela aplicação) |
| `dead-letter-queue` | `<queue>.dlq` | DLQ (quorum, durável, declarada pela aplicação); diferente de `queue` |
| `max-attempts` | `5` | tentativas de processamento por mensagem antes da DLQ; a primeira conta |
| `backoff.initial` | `200ms` | intervalo depois da primeira falha |
| `backoff.multiplier` | `2.0` | fator de crescimento do intervalo; finito e maior ou igual a 1,0 |
| `backoff.max` | `30s` | teto do intervalo; não menor que `backoff.initial` |
| `max-in-flight` | `50` | mensagens sem ack, igual ao `basicQos` |
| `workers` | `8` | threads de trabalho que executam as filas por chave |
| `confirm-timeout` | `5s` | espera do confirm da publicação na DLQ |

Durações aceitam `ms`, `s`, `m`, `h`, `d` ou ISO-8601. `InboxConfig.fromProperties` lança `IllegalArgumentException` com a chave recusada: `max-attempts`, `max-in-flight` ou `workers` menor que 1, `backoff.initial` ou `confirm-timeout` não positivo, `backoff.multiplier` `NaN`, infinito ou menor que 1,0, `backoff.max` menor que `backoff.initial`, fila ou DLQ em branco, DLQ igual à fila e `queue` ausente. Nenhum valor é meta de desempenho.

### Métricas e logs

Métricas Micrometer registradas na criação do `InboxMetrics`. Nenhuma tem valor-meta.

| Métrica | Tipo | Significado |
|---|---|---|
| `inbox.duplicates.discarded` | counter | mensagens descartadas porque o `messageId` já está na inbox |
| `inbox.dlq.messages` | counter | mensagens publicadas na DLQ, inclusive as da chave bloqueada |
| `inbox.process.retries` | counter | repetições do processamento de uma mensagem que falhou |

Cada tentativa roda com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` dos headers que a mensagem tem, removidas ao fim da tentativa. O backend de log (SLF4J) é do hospedeiro.

### Limites

- **Bloqueio de chaves só em memória (P7).** O estado das chaves bloqueadas vive em `InMemoryBlockedKeys` e não há liberação durante a execução. O reinício do consumidor libera todas as chaves: a próxima mensagem de uma chave com mensagem na DLQ é aplicada sem aquela mensagem, o que quebra a ordem da chave. O armazenamento além do reinício e a liberação depois da revisão manual estão em `.agents/context/pendencias.md` (P7), sem decisão; a interface `BlockedKeys` é o ponto onde a decisão entra.
- **Mensagens em retry ocupam vagas em voo.** Uma chave em espera segura as suas mensagens sem ack, e elas contam em `max-in-flight`; uma chave que falha por muito tempo reduz a vazão das demais até a DLQ.
- **Reentrega depois de queda do canal.** O broker devolve à fila as mensagens sem ack do canal perdido; a inbox descarta a reentrega, e uma publicação na DLQ da cópia antiga pode resultar em mensagem duplicada na DLQ.
- **DLQ.** A revisão e o reprocessamento das mensagens da DLQ são manuais.
- **Memória.** O consumidor limita as mensagens em voo pelo `basicQos`; `InboxMemoryIT` verifica o heap estável sob `-Xmx256m -XX:MaxDirectMemorySize=64m`.
- **Spring Boot.** A auto-configuração `InboxAutoConfiguration` cria o consumidor em aplicações Spring Boot; ver os READMEs de `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`.

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
| `com.h2database:h2` (teste) | 2.2.224, última linha com bytecode Java 8 |
| JUnit (teste) | 5.11.0 |
| Testcontainers | 2.0.5 |
| Logback (teste) | 1.3.16 |
| SQL Server | `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` |
| RabbitMQ | `rabbitmq:4.3.6-management` |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.10.0` |

Nos adaptadores Spring, o H2 de teste tem a versão gerenciada pelo BOM do Spring Boot. A aplicação hospedeira escolhe os drivers SQL Server e H2 do próprio classpath; no JDK 8 ela precisa da variante `jre8`, porque a `jre11` tem bytecode Java 11.

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
