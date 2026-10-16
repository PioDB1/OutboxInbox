# Contrato: API Java do módulo `mensageria-outbox`

Superfície pública usada pelo hospedeiro (Pagamentos). Pacote base `br.com.db1.poc.outbox`. Assinaturas de referência; a implementação pode acrescentar membros sem alterar estes.

## `envelope.Envelope`

Classe final imutável, com um acessor de mesmo nome por campo: `UUID messageId`, `UUID correlationId`, `String idempotencyKey`, `String aggregateKey`, `String type`, `short schemaVersion`, `Instant occurredAt`, `byte[] payload`, `String traceId`. O construtor rejeita (`IllegalArgumentException`) campo nulo, texto vazio e texto acima do tamanho da coluna correspondente (`idempotencyKey` 200, `aggregateKey` 100, `type` 100, `traceId` 64). `Envelope.novo(...)` gera `messageId` aleatório (UUID v4) quando a aplicação não o informa.

## `write.OutboxWriter`

```java
public final class OutboxWriter {
    public OutboxWriter(OutboxDialect dialect);
    public long insert(Connection connection, Envelope envelope) throws SQLException; // devolve o seq
}
```

Usa a conexão e a transação abertas pela aplicação. Lança `IllegalStateException` se `connection.getAutoCommit()` for `true`; não faz commit, rollback nem fecha a conexão.

## `relay.Relay`

```java
public interface Relay extends AutoCloseable {
    void start();                  // inicia a thread do relay; sem efeito se outbox.relay.enabled=false
    int runOnce();                 // um ciclo completo (lease, publicação, retenção, métricas); devolve linhas publicadas
    @Override void close();        // pára a thread e libera o lease
}
```

Fábrica: `RelayFactory.create(RelayConfig, DataSource, MessagePublisher, MeterRegistry, String holderId)` devolve `ClaimRelay` ou `ReadPointRelay` conforme `outbox.relay.strategy`.

## `relay.RelayConfig`

`RelayConfig.fromProperties(Properties)` lê as chaves `outbox.relay.*` do plano e lança `IllegalArgumentException` com os valores aceitos quando `strategy` está ausente ou é desconhecida.

## `publish.MessagePublisher`

```java
public interface MessagePublisher extends AutoCloseable {
    void publish(PublishableMessage message) throws PublishException; // retorna somente após o confirm positivo
}
```

`PublishableMessage` carrega os campos do `Envelope`. `PublishException` cobre confirm negativo, timeout de confirm, mensagem devolvida e erro de conexão.

## `rabbit.RabbitMessagePublisher` e `rabbit.RabbitTopology`

```java
public RabbitMessagePublisher(ConnectionFactory factory, String exchange, Duration confirmTimeout);
public final class RabbitTopology {
    public static void declareExchange(Channel channel, String exchange) throws IOException;
    public static void declareQuorumQueue(Channel channel, String queue, String exchange, String routingKey) throws IOException;
}
```

`ConnectionFactory` é o `com.rabbitmq.client.ConnectionFactory` configurado pelo hospedeiro, com a recuperação automática de conexão do cliente ligada.

## `schema.OutboxSchemaInstaller`

`static void install(DataSource dataSource) throws SQLException`: executa o script idempotente de [`data-model.md`](../data-model.md).

## Consulta de pendentes (somente leitura)

```java
public interface PendingQuery {
    PendingStats pending(DataSource dataSource, RelayStrategyType strategy) throws SQLException;
}
public final class PendingStats {
    public PendingStats(long count, Duration oldestAge);
    public long count();
    public Duration oldestAge();
}
```

Fonte de `outbox.pending` e `outbox.lag.seconds`, e interface que o reconciliador usa para verificar que nenhuma linha fica pendente com o broker disponível.

## Métricas

`outbox.lag.seconds` (gauge, segundos da linha pendente mais antiga, 0 sem pendentes), `outbox.pending` (gauge, linhas pendentes), `outbox.publish.retries` (counter, tentativas de publicação de uma linha que já falhou antes).

## Logs

Toda mensagem de log do relay sobre uma linha é emitida com MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`, limpo ao fim da tentativa.
