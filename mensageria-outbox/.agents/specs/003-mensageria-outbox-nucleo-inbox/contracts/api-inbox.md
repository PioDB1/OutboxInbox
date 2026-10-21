# Contrato: API Java do consumidor no `mensageria-outbox-core`

Superfície pública usada pelo hospedeiro. Pacote base `br.com.db1.poc.inbox`, com os subpacotes `dialect`, `rabbit`, `schema` e `metrics` (os títulos abaixo são relativos a esse pacote), e usa `br.com.db1.poc.shared` (`Envelope`, `PropertyReader`, `MessageMdc`, `SqlScripts`, `SqlServerErrors`, `RabbitTopology`); Java 8. Assinaturas de referência; a implementação pode acrescentar membros sem alterar estes. A mensagem consumida é a do contrato [`001/contracts/mensagem-publicada.md`](../../001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md).

## `InboxMessageHandler`

```java
public interface InboxMessageHandler {
    /** Aplica o efeito de negócio da mensagem. Executa dentro da transação local; uma exceção desfaz a transação. */
    void handle(Envelope envelope, Connection connection) throws Exception;
}
```

`connection` é a conexão JDBC da transação, a mesma em que o `message_id` é gravado na inbox. Com JPA no Spring, a conexão é a da sessão da transação Spring e o tratador pode ignorá-la. O tratador não faz commit, rollback nem fecha a conexão.

## `InboxTransactionExecutor`

```java
public interface InboxTransactionExecutor {
    /** Executa o trabalho em uma transação local: commit se retorna, rollback se lança. */
    void execute(TransactionWork work) throws Exception;

    interface TransactionWork {
        void run(Connection connection) throws Exception;
    }
}
```

- `JdbcInboxTransactionExecutor(DataSource)`: abre a conexão, desliga o `autoCommit`, executa o trabalho, confirma (ou desfaz) e fecha.
- No Spring, `SpringInboxTransactionExecutor` executa o trabalho em um `TransactionTemplate` com a conexão de `DataSourceUtils.getConnection(dataSource)`.
- O consumidor lança `IllegalStateException` quando a conexão recebida está em `autoCommit`.

## `InboxConsumer`

```java
public final class InboxConsumer implements AutoCloseable {
    public InboxConsumer(InboxConfig config, MessageSource source, InboxDialect dialect,
            InboxTransactionExecutor transactions, InboxMessageHandler handler,
            BlockedKeys blockedKeys, InboxMetrics metrics);
    public void start();           // confirma as filas, registra o consumidor e inicia as threads de trabalho
    @Override public void close(); // cancela o consumidor, pára as threads e fecha a porta de consumo
}
```

Mensagens em processamento no `close` ficam sem ack e o broker as reentrega; a inbox as descarta se já foram confirmadas (R7).

## `InboxConfig`

`InboxConfig.fromProperties(Properties)` lê as chaves `inbox.consumer.*` da tabela "Parâmetros" do [`plan.md`](../plan.md) e lança `IllegalArgumentException` para cada valor recusado (ver os critérios da história 5 da spec). Acessores: `queue()`, `deadLetterQueue()`, `maxAttempts()`, `backoffInitial()`, `backoffMultiplier()`, `backoffMax()`, `maxInFlight()`, `workers()`, `confirmTimeout()`.

## `MessageSource` e `Delivery` (porta de consumo, R14)

```java
public interface MessageSource extends AutoCloseable {
    void start(String queue, String deadLetterQueue, int maxInFlight, Consumer<Delivery> onDelivery) throws IOException;
    void ack(Delivery delivery) throws IOException;                       // depois do commit
    void requeue(Delivery delivery) throws IOException;                   // devolve ao broker sem processar
    void deadLetter(Delivery delivery, String failure, int attempts) throws IOException; // confirm positivo, depois ack da original
    @Override void close();
}

public interface Delivery {
    Map<String, Object> headers();   // valores textuais como String; schemaVersion como Number
    byte[] body();
}
```

`onDelivery` só enfileira e nunca bloqueia. Nenhum tipo do RabbitMQ aparece nestas assinaturas.

## `rabbit.RabbitMessageSource`

```java
public RabbitMessageSource(ConnectionFactory factory, Duration confirmTimeout);
```

`ConnectionFactory` é o `com.rabbitmq.client.ConnectionFactory` do hospedeiro, com a recuperação automática de conexão ligada. Comportamento:

- `start`: confirma as duas filas com `queueDeclarePassive`, aplica `basicQos(maxInFlight)` e registra o consumo com `basicConsume` e `autoAck=false`.
- `ack` e `requeue` saem pelo canal que recebeu a entrega, com as operações do canal serializadas por um lock do adaptador.
- `deadLetter`: publica no exchange padrão, com a DLQ como routing key, `mandatory=true`, corpo e headers originais mais `x-inbox-failure` (`max-attempts` ou `blocked-key`) e `x-inbox-attempts`, aguarda o confirm em um canal próprio com `confirmSelect` e só então dá o ack da original; falha, devolução (`basic.return`) ou timeout de confirm lançam `IOException` e a original permanece sem ack.
- `br.com.db1.poc.shared.rabbit.RabbitTopology.declareQuorumQueue(Channel, String)`: declara fila quorum durável sem ligação a exchange, para a DLQ.

## `BlockedKeys`

```java
public interface BlockedKeys {
    boolean isBlocked(String aggregateKey);
    void block(String aggregateKey);
}
```

`InMemoryBlockedKeys` guarda as chaves em um conjunto concorrente, sem liberação (P7).

## `dialect.InboxDialect`

```java
public interface InboxDialect {
    boolean exists(Connection connection, UUID messageId) throws SQLException;
    void insert(Connection connection, UUID messageId) throws SQLException;
    boolean isDuplicateKey(SQLException e);
}
```

Todo SQL da inbox vive nas implementações. As operações usam a conexão e a transação do chamador e não fazem commit nem rollback.

## `schema.InboxSchemaInstaller`

`static void install(DataSource dataSource, InboxDialect dialect) throws SQLException`: executa o script idempotente do dialeto de [`data-model.md`](../data-model.md).

## `metrics.InboxMetrics`

```java
public final class InboxMetrics {
    public static final String DUPLICATES_DISCARDED = "inbox.duplicates.discarded";
    public static final String DLQ_MESSAGES = "inbox.dlq.messages";
    public static final String PROCESS_RETRIES = "inbox.process.retries";
    public InboxMetrics(MeterRegistry registry);
    public void recordDuplicate();
    public void recordDeadLetter();
    public void recordRetry();
}
```

Contadores sem valor-meta, no padrão do `OutboxMetrics`.

## Spring (`br.com.db1.poc.outbox.spring`)

- `InboxAutoConfiguration`: registra, cada um com `@ConditionalOnMissingBean`, o `InboxDialect` (detectado pelo produto do `DataSource`), o `InboxTransactionExecutor` (`SpringInboxTransactionExecutor`, sobre o `PlatformTransactionManager` e o `DataSource` da aplicação), o `InboxMetrics` (sobre o `MeterRegistry` da aplicação, ou um registro simples na falta dele), o `BlockedKeys`, o `MessageSource` (`RabbitMessageSource` sobre o bean `outboxRabbitConnectionFactory`) e o `InboxConsumer` com o ciclo de vida (`InboxConsumerLifecycle`).
- O `InboxConsumer` só é criado com `inbox.consumer.queue` definida e um bean `InboxMessageHandler` na aplicação.
- `InboxProperties` lê `inbox.consumer.*` com os padrões do plano.
- Registro: linha `br.com.db1.poc.outbox.spring.InboxAutoConfiguration` no `AutoConfiguration.imports` de `mensageria-outbox-spring-boot3` e de `mensageria-outbox-spring-boot4`.
