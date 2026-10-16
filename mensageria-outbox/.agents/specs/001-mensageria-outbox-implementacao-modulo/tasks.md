# Tarefas: Mensageria Outbox — implementação do módulo produtor

Os casos de teste citados (`TC-n`) estão em [`test-cases.md`](./test-cases.md). Os testes de cada tarefa nascem na própria tarefa. As tarefas cujos testes de integração (`*IT`) usam SQL Server e RabbitMQ têm como precondição o Docker em execução.

- [x] **T1. Atualizar a documentação autoritativa**
  - Depends on: none
  - Alvo 1 — `.agents/skills/mensageria-outbox/SKILL.md`, seções "Entidades e dados" (Envelope padrão e "Broker") e regras R5 e R25: descrever o formato da mensagem publicada (corpo = bytes do payload; campos do envelope em headers e properties AMQP; exchange `direct` com routing key = `type`; publicação com `mandatory`) conforme `contracts/mensagem-publicada.md`. Origem: história 2, critério de publicação com headers `messageId`, `correlationId` e `traceId`.
  - Alvo 2 — `.agents/skills/mensageria-inbox/SKILL.md`, regra R16 e seção "Entidades e dados" (Envelope padrão): o consumidor lê o envelope dos headers e o payload do corpo da mensagem, como em `contracts/mensagem-publicada.md`. Origem: história 2 (contrato entre os dois módulos).
  - Alvo 3 — `.agents/skills/registro-de-adrs/SKILL.md` (critérios de abertura de ADR, "Ferramentas e artefatos") e `.agents/skills/testes-de-falha-resiliencia/SKILL.md` (onde vive a suíte, dependências de teste): trocar as citações de `mensageria-core` por `mensageria-outbox` e `mensageria-inbox`. Origem: decisão D9; história 8.
  - Alvo 4 — `.agents/skills/ambiente-docker-sqlserver-rabbitmq/SKILL.md`, seções "Serviços" e "Operação": registrar as imagens usadas (`mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`, `rabbitmq:4.3.6-management`, `ghcr.io/shopify/toxiproxy:2.10.0`), o caminho `/opt/mssql-tools/bin/sqlcmd` do SQL Server 2019 e as variáveis de porta do `compose.yaml`. Origem: história 9.

- [x] **T2. Build Maven raiz, módulo `mensageria-outbox` e `Envelope` validado**
  - Depends on: T1
  - Pré-requisito nomeado: `pom.xml` raiz multi-módulo (Java 21, `dependencyManagement` e `pluginManagement` com as versões do `plan.md`, `maven-enforcer-plugin` exigindo Java 21 e Maven 3.9), módulo `mensageria-outbox` com `junit-jupiter` e entradas de `target/` no `.gitignore`.
  - `Envelope` imutável com `traceId`, que rejeita campo nulo, texto vazio e texto acima do tamanho das colunas, e fábrica `Envelope.novo` que gera `messageId`.
  - Testes unitários do `Envelope`; verificação de que o enforcer rejeita JDK anterior ao 21. Casos: TC-7, TC-58.

- [x] **T3. Esquema SQL Server e inserção transacional na outbox**
  - Depends on: T2
  - Pré-requisitos nomeados: dependências `mssql-jdbc` (runtime), `testcontainers` e `testcontainers-mssqlserver`; classe de teste `Images` com as tags fixas; base de testes de integração com o SQL Server singleton; `maven-failsafe-plugin` com `-Xmx256m -XX:MaxDirectMemorySize=64m` no `argLine` e convenção `*Test`/`*IT`.
  - Script `schema.sql` idempotente com `outbox`, `outbox_lease` e `outbox_read_point` e `OutboxSchemaInstaller`, conforme `data-model.md`.
  - `OutboxDialect` com a inserção em SQL Server e `OutboxWriter`, que usa a transação aberta pela aplicação e rejeita conexão em autocommit.
  - Testes de integração de inserção confirmada, rollback, `seq` crescente, autocommit, `messageId` duplicado e instalação idempotente. Casos: TC-1, TC-2, TC-3, TC-6, TC-8, TC-9.

- [x] **T4. Porta de publicação e adaptador RabbitMQ**
  - Depends on: T2
  - Pré-requisitos nomeados: dependências `amqp-client` e `testcontainers-rabbitmq`; `RabbitMQ` singleton na base de testes e `rabbitmq:4.3.6-management` em `Images`.
  - `MessagePublisher`, `PublishableMessage` e `PublishException`; `RabbitMessagePublisher` com confirm por mensagem, `mandatory=true` e tratamento de `basic.return` como falha; `RabbitTopology` com exchange `direct` durável e fila `quorum` durável, conforme `contracts/mensagem-publicada.md`.
  - Testes unitários do mapeamento para headers e properties com `Channel` simulado, verificação da fronteira de pacotes (nenhum `org.springframework` no módulo; `com.rabbitmq` somente em `rabbit`) e teste de integração do publicador contra o RabbitMQ. Casos: TC-14, TC-16.

- [x] **T5. Relay com estratégia claim: publicação com confirm**
  - Depends on: T3, T4
  - `Relay` (`start`, `runOnce`, `close`), `RelayConfig.fromProperties` com `enabled`, `strategy`, `exchange`, `batch-size`, `poll-interval` e `confirm-timeout` (estratégia ausente ou inválida impede a inicialização com mensagem que cita os valores aceitos), `RelayFactory`, `ClaimRelay` com seleção `UPDLOCK`/`READPAST`/`ROWLOCK`, publicação em ordem de `seq`, marcação `sent_at` após o confirm e commit ao final do lote, e a thread própria do relay.
  - Falha de publicação, confirm negativo, mensagem devolvida e erro de conexão mantêm a linha pendente; queda entre o confirm e o registro republica a mensagem.
  - Testes de integração com `Toxiproxy` (`testcontainers-toxiproxy` e a imagem em `Images` entram como pré-requisito nomeado desta tarefa) e com `MessagePublisher` de teste. Casos: TC-4, TC-5, TC-10, TC-11, TC-12, TC-13, TC-15, TC-17.

- [x] **T6. Ordem por `aggregateKey` e nenhuma linha pulada na estratégia claim**
  - Depends on: T5
  - Sonda `READUNCOMMITTED` de linha anterior do mesmo `aggregateKey` sobre `IX_outbox_aggregate`, e bloqueio da mensagem posterior de uma chave enquanto a anterior está pendente ou em falha, com as demais chaves seguindo.
  - Testes de integração com transações concorrentes (T1 abre e confirma depois de T2) para `aggregateKey` iguais e diferentes, e com rollback da transação anterior. Casos: TC-18, TC-19, TC-20, TC-21, TC-22.

- [x] **T7. Relay com estratégia de ponto de leitura**
  - Depends on: T6
  - `ReadPointRelay` com limite seguro lido de `MIN_ACTIVE_ROWVERSION()` antes da leitura, leitura por faixa de `rv`, avanço do ponto até o prefixo contíguo publicado e conjunto limitado de linhas publicadas à frente do ponto; valor `read-point` em `RelayConfig` e em `RelayFactory`.
  - Os testes de ordem e de commit fora de ordem das tarefas T5 e T6 passam a rodar nas duas estratégias por classe base parametrizada pela implementação de `Relay`.
  - Teste de integração que registra a semântica de `MIN_ACTIVE_ROWVERSION()` na imagem fixada, com os valores observados para o ADR `0001`. Casos: TC-23, TC-24, TC-25, TC-26, TC-27, TC-28 e as variantes de ponto de leitura de TC-18 a TC-21.

- [x] **T8. Lease de relay único, relay habilitado por instância e reinício**
  - Depends on: T7
  - `LeaseManager` sobre `outbox_lease` com aquisição e renovação por `UPDATE` condicional no relógio do banco, liberação em `close()`, chaves `lease.duration` e `lease.renew-interval`; o relay só executa ciclos com o lease e só adquire o lease quando `enabled=true`.
  - Testes de integração com dois relays, assunção após expiração, sobreposição forçada com latch, relay desabilitado e reinício no meio de um lote, nas duas estratégias. Casos: TC-36, TC-37, TC-38, TC-39, TC-41, TC-42, TC-43.

- [x] **T9. Retry com backoff e acúmulo com o broker indisponível**
  - Depends on: T7
  - `BackoffPolicy` exponencial determinístico por `aggregateKey` (chaves `backoff.initial`, `backoff.multiplier`, `backoff.max`), zerado no sucesso, aplicado nas duas estratégias.
  - Testes unitários da `BackoffPolicy` e testes de integração com Toxiproxy (broker desabilitado e restaurado; `latency`, `timeout` e `reset_peer`) para acúmulo, ausência de perda e intervalos não decrescentes limitados pelo teto. Casos: TC-29, TC-30, TC-31, TC-32, TC-33, TC-34.

- [x] **T10. Retenção da outbox**
  - Depends on: T8
  - `OutboxRetention` executada pelo relay ativo em lotes (`retention.max-age`, `retention.interval`, `retention.batch-size`): na estratégia claim remove linhas com `sent_at` vencido; na de ponto de leitura remove linhas com `rv <= last_rv` vencidas; nenhuma remove linha pendente.
  - Teste unitário que confere os valores padrão de todas as chaves `outbox.relay.*` e testes de integração da limpeza nas duas estratégias. Casos: TC-40, TC-44, TC-45, TC-46, TC-47, TC-48.

- [x] **T11. Métricas de publicação e logs estruturados**
  - Depends on: T9
  - Pré-requisito nomeado: dependências `micrometer-core`, `slf4j-api` e `logback-classic` (teste).
  - `PendingQuery` e `OutboxMetrics` com `outbox.lag.seconds`, `outbox.pending` e `outbox.publish.retries`; MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` em volta de cada tentativa de publicação, limpo ao fim.
  - Testes de integração das métricas com o broker fora e restaurado e testes com `ListAppender` para o MDC nos logs do relay. Casos: TC-35, TC-49, TC-50, TC-51, TC-52, TC-53.

- [x] **T12. Carga com memória limitada**
  - Depends on: T11
  - Teste de integração de carga com 10 000 linhas pendentes e `batch-size=100` nas duas estratégias, executado com os limites de heap e de memória direta do failsafe e com três medições de heap; corrige o relay se o consumo crescer com o volume. Caso: TC-61.

- [x] **T13. Ambiente Docker, README do módulo e `AGENTS.md`**
  - Depends on: T9
  - `compose.yaml` com `sqlserver`, `rabbitmq` e `toxiproxy`, `.env.example`, entrada `.env` no `.gitignore` e README do módulo com os parâmetros `outbox.relay.*`, as versões fixadas e o uso pelo hospedeiro.
  - Teste unitário que compara as tags do `compose.yaml` com `Images`.
  - Atualização de `AGENTS.md` (estrutura do repositório e seção de variáveis de ambiente) para refletir `pom.xml` raiz, módulo, `compose.yaml` e `.env.example`.
  - Casos: TC-56, TC-57, TC-59, TC-60, TC-62.

- [x] **T14. ADR `0001` da estratégia de relay**
  - Depends on: T10, T12
  - `docs/adr/README.md` e `docs/adr/0001-estrategia-de-relay.md` com status `Proposto`, as alternativas e a seção "Observações da PoC" com os dados medidos das duas estratégias (cenário de commits fora de ordem, lag, pendentes, retries, tempo de recuperação, contenção, linhas de código, quantidade de testes) e a semântica de `MIN_ACTIVE_ROWVERSION()` registrada em T7.
  - Casos: TC-54, TC-55.
