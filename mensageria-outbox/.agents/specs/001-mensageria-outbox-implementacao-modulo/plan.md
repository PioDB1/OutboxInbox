# Plano: Mensageria Outbox — implementação do módulo produtor

Responde ao "como" da [`spec.md`](./spec.md). Contexto, alternativas e evidências de cada decisão estão em [`research.md`](./research.md); aqui ficam os veredictos.

## Estado atual do código

O repositório não tem código-fonte, `pom.xml`, `compose.yaml` nem `docs/adr/`; existem apenas o material de descoberta em `.agents/`, o spec de entrada e os arquivos herdados de template. O plano parte de zero para o build e para o módulo. Na estação de desenvolvimento há Maven 3.9.9 sobre JDK 21 (Corretto 21.0.6) e não há Docker; os testes de integração exigem Docker em execução.

## Stack e estrutura

Maven multi-módulo, Java 21 (`maven.compiler.release=21`), conforme `AGENTS.md`. Esta unidade cria o `pom.xml` raiz (agregador, `dependencyManagement`, `pluginManagement`) e o módulo `mensageria-outbox/`; os demais módulos entram nas suas unidades. Pacote base `br.com.db1.poc.outbox`, `groupId` `br.com.db1.poc`.

| Pacote | Responsabilidade |
|---|---|
| `envelope` | `Envelope` (record imutável, validação dos campos obrigatórios) |
| `write` | `OutboxWriter`: inserção na transação local da aplicação |
| `relay` | interface `Relay`, `RelayFactory`, `ClaimRelay`, `ReadPointRelay`, `RelayRunner` (laço, lease, backoff, métricas), `RelayConfig`, `RelayStrategyType`, `BackoffPolicy`, `PendingQuery` |
| `lease` | `LeaseManager` |
| `publish` | porta `MessagePublisher` e `PublishException` |
| `rabbit` | `RabbitMessagePublisher` e `RabbitTopology`: único pacote que importa `com.rabbitmq` |
| `dialect` | `OutboxDialect` (interface) e `dialect.sqlserver.SqlServerOutboxDialect`: único pacote com SQL |
| `retention` | `OutboxRetention` |
| `metrics` | `OutboxMetrics` (Micrometer) |
| `schema` | `OutboxSchemaInstaller` e o script `sqlserver/schema.sql` em `src/main/resources` |

O módulo recebe `javax.sql.DataSource` e `MessagePublisher` do hospedeiro e não gerencia pool de conexões. O relay roda em uma thread dedicada própria, iniciada por `Relay.start()` e encerrada por `Relay.close()`.

## Decisões técnicas

- **Forma de entrega**: biblioteca Java sem framework de aplicação, embutida pelo hospedeiro; sem executável nem endpoint no módulo.
- **Acesso a banco**: JDBC puro sobre `DataSource`, SQL isolado em `OutboxDialect`; `SqlServerOutboxDialect` é a única implementação (R20).
- **Contrato da mensagem**: corpo AMQP = bytes do payload; campos do envelope em headers e properties AMQP. Especificação em [`contracts/mensagem-publicada.md`](./contracts/mensagem-publicada.md).
- **Cliente RabbitMQ**: `com.rabbitmq:amqp-client` 5.37.0 (AMQP 0-9-1), publisher confirms por mensagem com `mandatory=true`; mensagem devolvida (`basic.return`) conta como falha de publicação.
- **Topologia**: exchange `direct` durável e routing key = `type`; filas `quorum` duráveis declaradas pela aplicação com `RabbitTopology`, com argumentos fixos para todos os declarantes.
- **Logs**: SLF4J API 2.0.20 com MDC (`messageId`, `correlationId`, `aggregateKey`, `traceId`); o backend de log é escolhido pelo hospedeiro.
- **SQL Server**: imagem `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`; é nela que a semântica de `min_active_rowversion()` é confirmada e registrada no ADR `0001`.
- **Estratégia claim**: `SELECT TOP (lote) ... WITH (UPDLOCK, READPAST, ROWLOCK)` por `seq` na transação do ciclo, sonda de linhas anteriores do mesmo `aggregateKey` com `READUNCOMMITTED`, publicação em ordem, marcação com `sent_at` e commit ao final do lote.
- **Estratégia de ponto de leitura**: limite seguro lido de `MIN_ACTIVE_ROWVERSION()` antes da leitura; leitura de `rv > ponto AND rv < limite`; o ponto avança até o último `rv` do prefixo contíguo publicado; somente `INSERT` na outbox.
- **Lease**: tabela `outbox_lease` com linha única `relay`, aquisição e renovação por `UPDATE` condicional com o relógio do banco (`SYSUTCDATETIME()`), sem token de fencing (R19).
- **Backoff**: exponencial determinístico por `aggregateKey` (inicial 200 ms, fator 2, teto 30 s), zerado no sucesso; sem jitter.
- **Retenção**: remoção em lotes de linhas enviadas além de `retention.max-age`, executada pelo relay ativo.
- **Métricas**: gauges `outbox.lag.seconds` e `outbox.pending` atualizados a cada ciclo por consulta ao banco; contador `outbox.publish.retries`.
- **Memória**: lote limitado (`batch-size`), nenhuma coleção sem teto; teste de carga com `-Xmx256m -XX:MaxDirectMemorySize=64m`.
- **Configuração**: `RelayConfig.fromProperties(Properties)` com chaves `outbox.relay.*` e valores da tabela abaixo; credenciais nunca passam pelo módulo.
- **Dependências e versões**: lista em [`research.md`](./research.md), seção "Versões das dependências".

### Parâmetros configuráveis

| Chave (`outbox.relay.`) | Padrão | Observação |
|---|---|---|
| `enabled` | `false` | liga o relay na instância; só deve ser `true` em banco primário/ativo |
| `strategy` | sem padrão | `claim` ou `read-point`; ausente ou inválida impede a inicialização |
| `exchange` | `mensageria.outbox` | exchange de publicação |
| `batch-size` | `100` | linhas por ciclo |
| `poll-interval` | `500ms` | espera entre ciclos sem trabalho |
| `confirm-timeout` | `5s` | espera pelo confirm de cada mensagem |
| `backoff.initial` / `backoff.multiplier` / `backoff.max` | `200ms` / `2.0` / `30s` | política de backoff |
| `lease.duration` / `lease.renew-interval` | `15s` / `5s` | lease do relay ativo |
| `retention.max-age` | `7d` | idade mínima de uma linha enviada para ser removida |
| `retention.interval` / `retention.batch-size` | `60s` / `1000` | frequência e tamanho do lote da limpeza |

## Modelo de dados

Tabelas `outbox` (com `rv ROWVERSION` e `sent_at`), `outbox_lease` e `outbox_read_point`, em SQL Server, criadas por script idempotente. Detalhes em [`data-model.md`](./data-model.md).

## Contratos externos

- Mensagem publicada no RabbitMQ (headers, properties, exchange, fila quorum): [`contracts/mensagem-publicada.md`](./contracts/mensagem-publicada.md).
- API Java exposta ao hospedeiro (`OutboxWriter`, `Relay`, `MessagePublisher`, `RabbitTopology`, `OutboxSchemaInstaller`, consulta de pendentes): [`contracts/api-modulo.md`](./contracts/api-modulo.md).

## Interface

Dispensada: o módulo não tem telas nem fluxos de interface.

## Infraestrutura transversal criada

- `pom.xml` raiz com `maven-enforcer-plugin` (Java 21 e Maven 3.9), `maven-compiler-plugin`, `maven-surefire-plugin` (testes unitários) e `maven-failsafe-plugin` (testes `*IT`, com o `argLine` de limites de memória).
- `compose.yaml` com `sqlserver`, `rabbitmq` e `toxiproxy`, imagens `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`, `rabbitmq:4.3.6-management` e `ghcr.io/shopify/toxiproxy:2.10.0`; `healthcheck` do SQL Server com `/opt/mssql-tools/bin/sqlcmd`; portas parametrizadas por `MSSQL_PORT`, `RABBITMQ_AMQP_PORT`, `RABBITMQ_MGMT_PORT` e `TOXIPROXY_API_PORT`.
- `.env.example` com `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` e as quatro portas, com valores fictícios; `.gitignore` com `.env` e `target/`.
- `docs/adr/README.md` e `docs/adr/0001-estrategia-de-relay.md` (status `Proposto`), no formato da skill `registro-de-adrs`.
- `mensageria-outbox/README.md` com os parâmetros, o contrato de uso e as versões fixadas de imagens e dependências.

## Estratégia de testes

Convenção: `*Test` (surefire, sem Docker) e `*IT` (failsafe, com Docker). Cada tarefa que entrega código inclui seus testes. As versões de imagem usadas por Testcontainers vêm da classe de teste `Images`, e um teste unitário compara essas versões com as do `compose.yaml`.

- **Unitário**: validação do `Envelope`; `BackoffPolicy` (sequência não decrescente e teto); `RelayConfig.fromProperties` (estratégia ausente ou inválida, valores padrão); mapeamento do `Envelope` para headers e properties em `RabbitMessagePublisher` com `Channel` simulado por `java.lang.reflect.Proxy`; fronteira de pacotes (nenhum fonte fora de `rabbit` importa `com.rabbitmq`, nenhum fonte do módulo importa `org.springframework`); MDC nos logs capturado por `ListAppender` do Logback.
- **Integração (Testcontainers)**: esquema e `OutboxWriter` (commit, rollback, conexão em autocommit); as duas estratégias por classe base parametrizada pela implementação de `Relay` (publicação com confirm, ordem por `aggregateKey`, linha de `seq` menor confirmando depois, falha e retry com backoff, republicação após queda entre publish e registro, broker indisponível e restaurado com Toxiproxy, rede instável); lease (um único ativo, assunção após expiração, sobreposição forçada por instância congelada com latch); retenção; métricas; relay desabilitado; reinício; teste de carga de memória; teste que registra a semântica de `min_active_rowversion()` com transação aberta e depois confirmada.
- **Fora desta unidade**: a suíte `FalhaAF1Test` a `FalhaAF7Test` e o reconciliador pertencem ao domínio Reconciliação; E2E depende de Pagamentos e Extrato.
- **Infraestrutura de teste a introduzir**: JUnit 5.14.4, Testcontainers 2.0.5 (`testcontainers`, `testcontainers-mssqlserver`, `testcontainers-rabbitmq`, `testcontainers-toxiproxy`), Logback 1.5.38 em escopo de teste; espera por condição com laço de polling e timeout explícito, sem `Thread.sleep` como sincronização.

## Impacto na documentação autoritativa

Há divergência deliberada, amparada por decisões registradas:

- **Formato da mensagem no broker** (decisão humana desta spec: corpo = bytes do payload, envelope em properties e headers). A skill `mensageria-outbox` não descreve o mapeamento e a skill `mensageria-inbox` (R16 e "Entidades e dados") fala em ler o envelope "nos headers e no envelope". Ambas recebem o mapeamento de [`contracts/mensagem-publicada.md`](./contracts/mensagem-publicada.md) na etapa de tarefas.
- **Módulo `mensageria-core` descartado** (decisão D9). As skills `registro-de-adrs` e `testes-de-falha-resiliencia` ainda citam `mensageria-core` e passam a citar `mensageria-outbox` e `mensageria-inbox`.
- **Versões e comandos fixados do ambiente** (SQL Server 2019 CU32, RabbitMQ 4.3.6, Toxiproxy 2.10.0, caminho do `sqlcmd`): a skill `ambiente-docker-sqlserver-rabbitmq` registra as versões usadas, como ela própria determina.
- **`AGENTS.md`**: a estrutura do repositório e a seção de variáveis de ambiente passam a refletir o `pom.xml` raiz, o módulo, o `compose.yaml` e o `.env.example`.
O spec de entrada `spec-caso-a-outbox-inbox.md` é material de negócio de origem e não é editado.
