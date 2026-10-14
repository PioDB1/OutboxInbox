# mensageria-outbox

Lado produtor da entrega atômica de mensagens do padrão outbox/inbox: envelope padrão, inserção na outbox na transação local da aplicação, relay com duas estratégias atrás de uma única interface, lease de relay único, publicação no RabbitMQ com publisher confirms, retry com backoff, retenção da outbox e métricas de publicação.

É uma biblioteca Java 21 sem Spring Boot nem executável próprio. A aplicação hospedeira fornece o `DataSource` (SQL Server), o `MessagePublisher` e o `MeterRegistry`, e controla o ciclo de vida do relay. Contexto e decisões: `.agents/specs/001-mensageria-outbox-implementacao-modulo/` e a skill `.agents/skills/mensageria-outbox/SKILL.md`.

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
| Java | 21 |
| Maven | 3.9 ou superior |
| `com.rabbitmq:amqp-client` | 5.37.0 |
| `com.microsoft.sqlserver:mssql-jdbc` | 12.10.2.jre11 (runtime) |
| `io.micrometer:micrometer-core` | 1.17.1 |
| `org.slf4j:slf4j-api` | 2.0.20 |
| JUnit | 5.14.4 |
| Testcontainers | 2.0.5 |
| Logback (teste) | 1.5.38 |
| SQL Server | `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` |
| RabbitMQ | `rabbitmq:4.3.6-management` |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.10.0` |

As imagens do `compose.yaml` na raiz e as da classe de teste `Images` são as mesmas; `ComposeImagesTest` confere a igualdade.

## Ambiente local e testes

```
cp .env.example .env            # ajuste as credenciais; o .env não é versionado
docker compose up -d --wait     # sqlserver, rabbitmq e toxiproxy saudáveis
mvn clean verify                # a partir da raiz: testes *Test (surefire) e *IT (failsafe)
```

Os testes `*IT` sobem os próprios contêineres com Testcontainers, com credenciais efêmeras (ou as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER` e `RABBITMQ_DEFAULT_PASS`, quando definidas), e exigem Docker em execução; sem Docker eles são ignorados. O failsafe roda com `-Xmx256m -XX:MaxDirectMemorySize=64m`, os limites sob os quais `RelayMemoryIT` verifica que o relay não cresce em memória com o volume processado.

O Toxiproxy do `compose.yaml` expõe a API de controle em `TOXIPROXY_API_PORT` e o proxy `rabbitmq` em `TOXIPROXY_RABBITMQ_PORT`; a aplicação aponta para essa porta para sofrer os toxics.
