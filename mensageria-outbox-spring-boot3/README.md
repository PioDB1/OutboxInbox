# mensageria-outbox-spring-boot3

Auto-configuração da biblioteca [`mensageria-outbox`](../mensageria-outbox/README.md) para aplicações **Spring Boot 3.x**: gravação da outbox na transação da aplicação, envio assíncrono ao RabbitMQ após o commit e recuperação dos pendentes na inicialização.

| | |
|---|---|
| Coordenadas | `br.com.db1.poc:mensageria-outbox-spring-boot3:0.1.0-SNAPSHOT` |
| Linha do Spring Boot | 3.x (compilado e testado com 3.5.16) |
| Java (piso) | 17 (`--release 17`) |
| Dependência de compilação | `mensageria-outbox` (traz `com.rabbitmq:amqp-client` 5.37.0) |
| Dependências `provided` | `spring-boot-autoconfigure`, `spring-boot-starter-amqp`, `spring-boot-starter-jdbc` |

O Spring fica como `provided`: em execução vale a versão do Spring Boot 3.x da aplicação, que declara os starters `amqp` e `jdbc` (ou `data-jpa`) e o driver do SQL Server. Para aplicações Spring Boot 4.x, o artefato é o `mensageria-outbox-spring-boot4`.

## Uso pela aplicação

### Dependência

```xml
<properties>
    <!-- O BOM do Spring Boot gerencia o amqp-client (3.5.16 → 5.25.0); a biblioteca é compilada e testada com 5.37.0. -->
    <rabbit-amqp-client.version>5.37.0</rabbit-amqp-client.version>
</properties>

<dependency>
    <groupId>br.com.db1.poc</groupId>
    <artifactId>mensageria-outbox-spring-boot3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A propriedade `rabbit-amqp-client.version` vale para aplicações com `spring-boot-starter-parent`; quem importa o BOM `spring-boot-dependencies` fixa `com.rabbitmq:amqp-client` 5.37.0 no próprio `dependencyManagement`. O artefato é resolvido do repositório `JFrog-Sisprime` quando publicado; localmente, vem do `mvn install` descrito em "Código e build".

### Configuração

`spring.datasource.*` (SQL Server) e `spring.rabbitmq.*` (host, porta, usuário, senha, virtual host) são as chaves usuais do Spring Boot, com as credenciais em variáveis de ambiente. Os parâmetros de publicação são opcionais:

| Chave | Padrão | Descrição |
|---|---|---|
| `outbox.relay.exchange` | `mensageria.outbox` | exchange de publicação, declarado na inicialização |
| `outbox.relay.confirm-timeout` | `5s` | espera pelo confirm de cada mensagem |
| `outbox.relay.backoff.initial` | `200ms` | primeiro intervalo entre tentativas de uma mensagem |
| `outbox.relay.backoff.multiplier` | `2.0` | fator de crescimento do intervalo |
| `outbox.relay.backoff.max` | `30s` | teto do intervalo |
| `outbox.relay.batch-size` | `100` | linhas por lote da recuperação na inicialização |

As demais chaves `outbox.relay.*` da biblioteca (`enabled`, `strategy`, `lease.*`, `retention.*`, `poll-interval`) pertencem ao relay por polling e não têm efeito aqui.

### Esquema

As tabelas `outbox`, `outbox_lease` e `outbox_read_point` são criadas por script DDL executado no banco antes de subir a aplicação, com a mesma estrutura do `schema.sql` da biblioteca (`mensageria-outbox/src/main/resources/br/com/db1/poc/outbox/schema/sqlserver/schema.sql`). A auto-configuração não chama o `OutboxSchemaInstaller`.

### Filas

A aplicação declara uma fila quorum por tipo de evento, com a fábrica `outboxRabbitConnectionFactory`. A publicação usa `mandatory=true`: sem fila ligada à routing key (o `type` do envelope), cada envio falha e se repete indefinidamente.

```java
@Configuration(proxyBeanMethods = false)
class MensageriaConfiguration {

    record FilaPagamentoRecebido() {}

    // Depende do outboxPublisher para declarar a fila depois do exchange.
    @Bean
    FilaPagamentoRecebido filaPagamentoRecebido(ConnectionFactory outboxRabbitConnectionFactory,
            OutboxRelayProperties props, RabbitMessagePublisher outboxPublisher) throws IOException, TimeoutException {
        try (Connection connection = outboxRabbitConnectionFactory.newConnection();
                Channel channel = connection.createChannel()) {
            RabbitTopology.declareQuorumQueue(channel, "extrato.pagamento-recebido", props.exchange(), "PagamentoRecebido");
        }
        return new FilaPagamentoRecebido();
    }
}
```

`ConnectionFactory`, `Connection` e `Channel` são de `com.rabbitmq.client`.

### Gravação

```java
@Transactional
public void registrar(Pagamento pagamento) {
    repository.saveAndFlush(pagamento);   // com JPA, o flush faz uma violação de restrição falhar antes da outbox
    outboxGravador.gravar(Envelope.novo(correlationId, idempotencyKey, pagamento.getId().toString(),
            "PagamentoRecebido", (short) 1, occurredAt, payloadBytes, traceId));
}
```

Com o commit, o dado de negócio e a linha da outbox são gravados juntos e a mensagem é enviada; com o rollback, nenhum dos dois existe e nada é enviado.

## Comportamento

Beans registrados, todos com `@ConditionalOnMissingBean`: `SqlServerOutboxDialect`, `OutboxWriter`, `outboxRabbitConnectionFactory`, `outboxPublisher` (`RabbitMessagePublisher`), `OutboxGravador`, `OutboxDespachante` e `OutboxRecuperacao`.

- **`outboxRabbitConnectionFactory`** — `com.rabbitmq.client.ConnectionFactory` preenchido a partir de `spring.rabbitmq.*`, com a recuperação automática de conexão ligada, que o contrato da biblioteca exige. A fábrica interna do `CachingConnectionFactory` do Spring AMQP desliga essa recuperação e por isso não é reaproveitada.
- **`outboxPublisher`** — declara o exchange na criação do bean; o RabbitMQ precisa estar disponível na inicialização da aplicação.
- **`OutboxGravador.gravar(envelope)`** — exige transação Spring ativa (fora dela, `IllegalTransactionStateException`). Grava pela conexão que o gerenciador de transação expõe para o `DataSource` (`DataSourceUtils.getConnection`): a do `DataSourceTransactionManager` ou, com JPA, a da sessão Hibernate do `JpaTransactionManager`. Devolve o `seq`, traduz `SQLException` para `DataAccessException` e publica o evento `OutboxGravada`.
- **`OutboxDespachante`** — `@TransactionalEventListener(AFTER_COMMIT)`: entrega a mensagem a um agendador próprio de thread única (`outbox-despacho-`), que publica, espera o confirm positivo e marca `sent_at` em conexão própria em autocommit. Falha de publicação é retentada com intervalo `min(max, initial × multiplier^(n-1))`, sem limite de tentativas e sem descarte. Cada tentativa roda com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`.
- **Ordem por `aggregateKey`** — as mensagens da mesma chave são publicadas uma de cada vez, em ordem de `seq`; cada uma só sai depois do confirm e da marcação de `sent_at` da anterior, e uma mensagem em falha ou em espera retém as posteriores da mesma chave, enquanto as demais chaves seguem. Antes de publicar, o despachante consulta a outbox (`hasPendingBetween`, com `READUNCOMMITTED`, pelo índice `IX_outbox_aggregate`): uma linha pendente anterior da mesma chave que ele ainda não recebeu — de transação ainda não confirmada ou de execução anterior que a recuperação ainda não leu — retém a chave, verificada de novo com o mesmo intervalo crescente ou assim que uma linha da chave chega ao despachante. A mesma consulta descarta a linha já marcada como enviada, e uma linha entregue duas vezes (após o commit e pela recuperação) é publicada uma vez.
- **`OutboxRecuperacao`** — no `ApplicationReadyEvent`, em thread própria (`outbox-recuperacao`), lê as linhas com `sent_at IS NULL` em lotes de `batch-size`, em ordem de `seq`, e só lê o lote seguinte depois de as linhas do lote atual serem confirmadas e marcadas.

A entrega é at-least-once: uma mensagem confirmada cuja marcação de `sent_at` falhou não é republicada na hora; a linha fica pendente e é publicada de novo pela recuperação da próxima inicialização. Como a linha continua pendente, ela retém as posteriores do mesmo `aggregateKey` até essa recuperação. A recuperação para quando encontra essa situação, e as pendentes restantes também ficam para a próxima inicialização.

## Restrições

- Uma linha pendente anterior retém a sua chave enquanto existir: uma transação longa ainda aberta segura as mensagens posteriores da mesma chave até o commit ou o rollback.
- A aplicação não instancia o relay por polling da biblioteca (`RelayFactory`) junto desta auto-configuração: os dois competiriam pelas mesmas linhas pendentes.
- Esta auto-configuração não executa a retenção da outbox nem registra as métricas Micrometer de publicação, que pertencem ao relay por polling; as linhas enviadas permanecem na tabela.
- A conexão usada na gravação pertence à transação; obtida fora dela, chega em autocommit e o `OutboxWriter` a recusa com `IllegalStateException`.

## Código e build

O código comum às duas linhas do Spring Boot (pacote `br.com.db1.poc.outbox.spring`) vive em [`../mensageria-outbox-spring-boot-shared/`](../mensageria-outbox-spring-boot-shared/README.md) e entra neste módulo pelo `build-helper-maven-plugin`. Este módulo contém só `OutboxRabbitConnectionFactoryConfiguration`, que usa o `RabbitProperties` do Spring Boot 3.x (`org.springframework.boot.autoconfigure.amqp`), e o arquivo `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

```
mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install   # a partir da raiz, em JDK 21
```

`OutboxAutoConfigurationTest` verifica a auto-configuração com `ApplicationContextRunner`, sem banco nem broker. O repositório não tem aplicação Spring Boot 3.x: este artefato é verificado só por esse teste, e a validação de ponta a ponta contra SQL Server e RabbitMQ foi feita com o artefato Spring Boot 4.x, que compartilha o mesmo código.
