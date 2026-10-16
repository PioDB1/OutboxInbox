---
name: integracao-mensageria-outbox-spring-boot
description: Como integrar a biblioteca mensageria-outbox ao Spring Boot 4.1.1 com Spring Data JPA e SQL Server. Use ao gravar, publicar ou recuperar eventos pela outbox, ao criar as tabelas da outbox pelo script sql/outbox.sql, ao configurar RabbitTopology, OutboxGravador, OutboxWriter na conexão JDBC da transação JPA (@Transactional), disparo assíncrono após o commit, RabbitMessagePublisher com publisher confirms, marcação de sent_at, reenvio dos pendentes na inicialização e propriedades outbox.relay.*.
metadata:
  author: clovis-cli
  type: technical-skill
---

# Integração do mensageria-outbox com Spring Boot

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão: novo passo no ciclo de gravação e publicação, outra forma de obter a conexão da transação, outra política de reenvio, outra fonte de configuração. Refactor técnico que preserva o how-to (renomear classes do adaptador, mover pacotes, extrair helpers) não pede atualização. Se esta skill disser X e o código ou o contrato da biblioteca fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral do padrão

A biblioteca `br.com.db1.poc:mensageria-outbox:0.1.0-SNAPSHOT` não depende de framework: a aplicação hospedeira fornece o `DataSource`, a conexão da transação e o `ConnectionFactory` do RabbitMQ, e controla o ciclo de vida. O demo integra a biblioteca por um **adaptador fino** escrito no próprio projeto, no pacote `br.com.db1.demooutboxinbox.outbox`, que faz quatro coisas:

1. **Declara a topologia** do RabbitMQ (exchange e filas).
2. **Grava a linha da outbox** com o `OutboxWriter` na mesma conexão JDBC da transação JPA do dado de negócio.
3. **Dispara a publicação de forma assíncrona após o commit**, repetindo o envio até o confirm do broker e marcando `sent_at` em seguida.
4. **Recupera os pendentes** (`sent_at IS NULL`) na inicialização e os entrega ao mesmo envio assíncrono.

As tabelas da outbox são criadas fora da aplicação, pelo script `sql/outbox.sql` executado manualmente no banco.

O fluxo de referência é o diagrama `../mensageria-outbox/Outbox-Assync.puml`. O relay por polling da biblioteca (`RelayFactory`, `ClaimRelay`, `ReadPointRelay`, estratégias `claim`/`read-point`) fica desligado com `outbox.relay.enabled=false` e não é instanciado: a publicação acontece só pelo adaptador descrito aqui.

A garantia é **at-least-once**: a mesma mensagem pode ser publicada mais de uma vez (queda entre o confirm e a marcação de `sent_at`, ou envio após o commit concorrendo com a recuperação).

## Como aplicar

### 1. Dependências e configuração

- O `pom.xml` declara `spring-boot-starter-data-jpa`, `spring-boot-starter-amqp`, `mssql-jdbc` e `br.com.db1.poc:mensageria-outbox:0.1.0-SNAPSHOT` (resolvida do repositório `JFrog-Sisprime`). O driver SQL Server usado é o do classpath do demo, com a versão gerenciada pelo Spring Boot.
- O `amqp-client` é fixado em 5.37.0 pela propriedade `rabbit-amqp-client.version` do `pom.xml`, a versão com que a biblioteca é construída e testada. Sem a propriedade, o `spring-boot-starter-parent` 4.1.1 resolve a 5.30.0.
- O `DataSource` é o auto-configurado pelo Spring Boot (`spring.datasource.*`); a transação é a do `JpaTransactionManager` auto-configurado.
- A configuração fica em `src/main/resources/application.properties`. Credenciais chegam por variáveis de ambiente (as de `../compose.yaml`: `MSSQL_SA_PASSWORD`, `MSSQL_PORT`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_AMQP_PORT`) referenciadas por placeholders, nunca por valores literais.
- O `application.properties` importa o `.env` da raiz do repositório como fonte de configuração, com `spring.config.import=optional:file:../.env[.properties]`; o arquivo é lido no formato `.properties`. As variáveis de ambiente do processo têm precedência sobre os valores do `.env`. O caminho `../.env` é relativo ao diretório de trabalho: executada a partir de outro diretório (por exemplo, pela IDE), a aplicação não encontra o arquivo e depende das variáveis exportadas no ambiente.
- Os parâmetros de publicação usam as chaves `outbox.relay.*` da biblioteca, com os mesmos padrões do `../mensageria-outbox/README.md`, ligadas por um `@ConfigurationProperties(prefix = "outbox.relay")` do adaptador:

| Chave | Padrão | Uso no adaptador |
|---|---|---|
| `enabled` | `false` | declarada explicitamente como `false`; mantém o relay por polling desligado |
| `exchange` | `mensageria.outbox` | exchange da topologia e do `RabbitMessagePublisher` |
| `confirm-timeout` | `5s` | espera pelo confirm de cada publicação |
| `backoff.initial` | `200ms` | primeiro intervalo de reenvio de uma mensagem |
| `backoff.multiplier` | `2.0` | fator de crescimento do intervalo |
| `backoff.max` | `30s` | teto do intervalo |
| `batch-size` | `100` | linhas lidas por lote na recuperação da inicialização |

`RelayConfig.fromProperties` não é usado: ele exige `outbox.relay.strategy`, que só faz sentido para o relay por polling.

### 2. Esquema das tabelas

- As tabelas `outbox`, `outbox_lease` e `outbox_read_point` e os índices da `outbox` são criados pelo script `sql/outbox.sql`, executado manualmente no banco da aplicação antes de subi-la (por exemplo, `sqlcmd -S localhost,1433 -U sa -d master -i sql/outbox.sql`). O script é idempotente e separa os lotes com `GO`, interpretado pelo `sqlcmd` e pelo SSMS.
- O script tem a mesma estrutura do `schema.sql` da biblioteca, que o `SqlServerOutboxDialect` lê e escreve. Uma nova versão da biblioteca com outro `schema.sql` exige atualizar o script na mesma mudança.
- A aplicação não chama o `OutboxSchemaInstaller`: a criação das tabelas pertence a quem administra o banco. Não há Flyway nem Liquibase.
- As tabelas da outbox não são entidades JPA: são lidas e escritas somente pela API da biblioteca (`OutboxWriter` e `SqlServerOutboxDialect`), e o `ddl-auto=update` do Hibernate não as toca.

### 3. Topologia do RabbitMQ e publicador

- Crie um bean `com.rabbitmq.client.ConnectionFactory` próprio do adaptador, preenchido a partir do `RabbitProperties` do Spring Boot (`spring.rabbitmq.host`, `port`, `username`, `password`, `virtual-host`) e com `setAutomaticRecoveryEnabled(true)`. Não reaproveite a fábrica interna do `CachingConnectionFactory` do Spring AMQP: ela desliga a recuperação automática do cliente, que o contrato da biblioteca exige ligada.
- Na inicialização, abra uma conexão e um canal dessa fábrica e declare a topologia com `RabbitTopology`, fechando o canal e a conexão em seguida. O adaptador declara o exchange no método `@Bean` do publicador (`OutboxConfiguration`); cada fila é declarada pelo domínio que publica o evento:

```java
try (com.rabbitmq.client.Connection connection = rabbitConnectionFactory.newConnection();
		Channel channel = connection.createChannel()) {
	RabbitTopology.declareExchange(channel, props.exchange());
	RabbitTopology.declareQuorumQueue(channel, fila, props.exchange(), tipoDoEvento);
}
```

- Cada tipo de evento publicado precisa de ao menos uma fila ligada com a routing key igual ao `type` do envelope (o exemplo do README liga `extrato.pagamento-recebido` a `PagamentoRecebido`). O nome da fila e o tipo pertencem ao domínio que publica o evento.
- Declare as filas somente com `RabbitTopology.declareQuorumQueue`. Uma declaração da mesma fila por `Queue`/`Declarables` do Spring AMQP com argumentos diferentes de `x-queue-type=quorum`, `durable=true`, `exclusive=false` e `auto-delete=false` recebe `PRECONDITION_FAILED` do broker.
- Registre um único `RabbitMessagePublisher(rabbitConnectionFactory, props.exchange(), props.confirmTimeout())` como bean singleton, fechado no encerramento do contexto (`destroyMethod = "close"`). O publicador abre a conexão na primeira publicação e a reabre depois de uma falha.

### 4. Gravação na transação JPA

- O método de serviço que grava o dado de negócio é `@Transactional` e grava o envelope na outbox dentro da mesma transação com `OutboxGravador.gravar(envelope)`:

```java
@Transactional
public void registrar(...) {
	repository.saveAndFlush(entidade);
	outboxGravador.gravar(envelope);
}
```

- O `OutboxGravador` insere a linha pela conexão JDBC da sessão Hibernate e publica o evento de aplicação `OutboxGravada(seq, envelope)`, tratado depois do commit:

```java
long seq = entityManager.unwrap(Session.class)
		.doReturningWork(connection -> outboxWriter.insert(connection, envelope));
eventos.publishEvent(new OutboxGravada(seq, envelope));
```

- `OutboxWriter` é um bean singleton construído com `new OutboxWriter(dialect)`, sobre o bean `SqlServerOutboxDialect` compartilhado com o despachante e a recuperação.
- Use `saveAndFlush` (ou `entityManager.flush()`) antes do `insert`, para que o identificador do dado de negócio exista e uma violação de restrição falhe antes da gravação da outbox. A atomicidade não depende do flush: qualquer falha antes do commit desfaz o dado de negócio e a linha da outbox.
- Commit, rollback e fechamento da conexão são do Spring. O adaptador não chama `commit`, `rollback`, `close` nem `setAutoCommit` na conexão da transação.
- O `Envelope` chega pronto do domínio (`Envelope.novo(correlationId, idempotencyKey, aggregateKey, type, schemaVersion, occurredAt, payload, traceId)`); todos os textos, inclusive `traceId`, são obrigatórios e não vazios, e o payload é gravado como bytes.
- `OutboxGravador.gravar` usa `@Transactional(propagation = MANDATORY)`: chamado fora de transação, lança `IllegalTransactionStateException` antes de tocar a conexão. Trate isso como erro de programação (falta de `@Transactional` em quem chama), nunca abrindo uma transação manual para contorná-lo. Pela mesma razão, `OutboxWriter.insert` lança `IllegalStateException` com a conexão em `autoCommit`.

### 5. Disparo assíncrono após o commit

- O evento `OutboxGravada` é tratado por um `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` do `OutboxDespachante`, que apenas entrega a mensagem ao envio assíncrono e retorna. O listener roda na thread da requisição e não publica no broker diretamente.
- O despachante usa um `ThreadPoolTaskScheduler` próprio, criado e encerrado por ele e não registrado como bean (um bean `Executor` ocuparia o lugar do executor de tarefas auto-configurado pelo Spring Boot), com uma thread: o `RabbitMessagePublisher.publish` é `synchronized` sobre um único canal, e threads adicionais só ficariam bloqueadas nele. Cada tentativa:
  1. coloca no MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` (com `MDC.putCloseable`, removidos ao fim da tentativa);
  2. chama `publisher.publish(PublishableMessage.from(envelope))`, que só retorna depois do confirm positivo;
  3. com sucesso, marca a linha como enviada com `dialect.markSent(connection, List.of(seq))` em uma conexão própria do `DataSource`, com `setAutoCommit(true)`, fora de qualquer transação JPA;
  4. com `PublishException`, registra o aviso e reagenda a mesma mensagem com o intervalo `new BackoffPolicy(initial, multiplier, max, 1).delayAfter(tentativas)`.
- A mensagem é reenviada até o confirm, sem limite de tentativas e sem descarte. Uma falha ao marcar `sent_at` depois do confirm não republica a mensagem; a linha continua pendente e é reenviada pela recuperação da próxima inicialização.
- O agendador e o publicador são encerrados com o contexto; mensagens ainda em reenvio ficam pendentes no banco e são recuperadas na inicialização seguinte.

### 6. Recuperação dos pendentes na inicialização

- Ao receber `ApplicationReadyEvent`, o adaptador lê as linhas pendentes e as entrega ao mesmo despachante assíncrono, sem bloquear a inicialização.
- A leitura usa a API da biblioteca, em lotes de `outbox.relay.batch-size`:
  1. abre uma conexão do `DataSource` com `setAutoCommit(false)`, chama `dialect.claimPending(connection, batchSize)` e faz `commit` imediatamente, antes de despachar. `claimPending` usa `UPDLOCK, READPAST, ROWLOCK`: os bloqueios duram até o fim da transação e impediriam o `markSent` do despachante;
  2. entrega cada `OutboxRow` (`row.seq()`, `row.envelope()`) ao despachante;
  3. espera o lote inteiro ser confirmado e marcado antes de ler o próximo, porque `claimPending` sempre devolve as primeiras linhas com `sent_at IS NULL`; o laço termina quando a leitura devolve uma lista vazia.
- Se uma linha do lote foi confirmada mas a marcação de `sent_at` falhou, a recuperação para depois desse lote: a linha voltaria na leitura seguinte e seria republicada na mesma inicialização. As pendentes restantes são publicadas na próxima inicialização.
- A recuperação roda em uma thread própria (`OutboxRecuperacao`), diferente da thread do despachante, porque espera os envios que ele executa.
- `READPAST` ignora as linhas de transações ainda abertas; essas linhas são publicadas pelo próprio disparo após o commit.

## Ferramentas e artefatos envolvidos

- Biblioteca `mensageria-outbox` (fonte em `../mensageria-outbox/src/main/java/br/com/db1/poc/outbox/`): `envelope.Envelope`, `write.OutboxWriter`, `dialect.sqlserver.SqlServerOutboxDialect` (`insert`, `claimPending`, `markSent`), `dialect.OutboxRow`, `rabbit.RabbitTopology`, `rabbit.RabbitMessagePublisher`, `publish.PublishableMessage`, `publish.PublishException` e `relay.BackoffPolicy`.
- Contrato de uso pelo hospedeiro e parâmetros `outbox.relay.*`: `../mensageria-outbox/README.md`. Fluxo de disparo assíncrono: `../mensageria-outbox/Outbox-Assync.puml`. Esquema SQL: script `sql/outbox.sql` do demo, com a estrutura de `../mensageria-outbox/src/main/resources/br/com/db1/poc/outbox/schema/sqlserver/schema.sql`.
- Adaptador do demo, em `src/main/java/br/com/db1/demooutboxinbox/outbox/`: `OutboxConfiguration` (dialeto, `OutboxWriter`, `ConnectionFactory` do RabbitMQ, exchange e publicador), `OutboxRelayProperties` (`outbox.relay.*`), `OutboxGravador` e `OutboxGravada` (gravação na transação), `OutboxDespachante` (envio após o commit) e `OutboxRecuperacao` (pendentes na inicialização).
- Spring: `@Transactional` com `JpaTransactionManager`, `Session.doReturningWork` do Hibernate, `ApplicationEventPublisher`, `@TransactionalEventListener`, `ThreadPoolTaskScheduler`, `ApplicationReadyEvent`, `RabbitProperties` e `@ConfigurationProperties`.
- SLF4J com MDC para os logs de publicação; o padrão de log da aplicação inclui `%X{messageId}`, `%X{correlationId}`, `%X{aggregateKey}` e `%X{traceId}`.
- Ambiente local: `../compose.yaml` (SQL Server e RabbitMQ), com credenciais no `../.env`, não versionado.

## Restrições e armadilhas

- **Tabelas ausentes.** Sem o `sql/outbox.sql` executado, a aplicação sobe, toda gravação na outbox falha e desfaz a transação do dado de negócio, e a recuperação registra um erro ao ler os pendentes.
- **Duplicidade é esperada.** O envio após o commit e a recuperação podem publicar a mesma linha, assim como uma queda entre o confirm e o `markSent`. O consumidor deduplica pelo `messageId`; o adaptador não tenta evitar a duplicidade.
- **Sem ordem por `aggregateKey`.** Cada linha é despachada e reenviada de forma independente; a garantia de ordem por `aggregateKey` da biblioteca pertence ao relay por polling, que está desligado. Um evento novo que exija ordem entre mensagens do mesmo `aggregateKey` é uma decisão a escalar antes da implementação.
- **Fila obrigatória.** A publicação usa `mandatory=true`: sem fila ligada à routing key, o broker devolve a mensagem, `publish` lança `PublishException` e o reenvio se repete indefinidamente.
- **Evento fora de transação.** `@TransactionalEventListener` ignora eventos publicados sem transação ativa; a mensagem só seria publicada na recuperação da próxima inicialização.
- **Bloqueio na recuperação.** Despachar linhas com a transação do `claimPending` ainda aberta trava o `markSent` do despachante.
- **Memória durante indisponibilidade do broker.** As mensagens em reenvio ficam no agendador até o confirm; o estado durável é sempre a linha da outbox, e a aplicação continua gravando normalmente.
- **Métricas do relay ausentes.** `outbox.lag.seconds`, `outbox.pending` e `outbox.publish.retries` são emitidas pelo relay por polling e não existem neste caminho.
- **Relay por polling.** Não instancie `RelayFactory`, `ClaimRelay` nem `ReadPointRelay` junto deste adaptador: eles competiriam pelas mesmas linhas pendentes com outra política de envio.
