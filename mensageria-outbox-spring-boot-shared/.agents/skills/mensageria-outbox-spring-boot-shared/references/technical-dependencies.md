# Dependências técnicas da integração Spring Boot da outbox e do consumidor

> **Maintaining this file**
>
> Atualize esta lista sempre que uma precondição técnica da integração surgir, deixar de existir ou mudar o efeito da sua ausência. Mudanças de implementação que preservam a precondição não pedem atualização. Divergência entre esta lista e o projeto sem decisão registrada é escalada para decisão humana.

Cada item diz o que é a dependência e o que deixa de funcionar quando ela falta.

1. **Bean `DataSource` na aplicação** (pelo `spring-boot-starter-jdbc` ou `spring-boot-starter-data-jpa`) — `OutboxGravador`, `OutboxDespachante` e `OutboxRecuperacao` o recebem no construtor; sem ele a auto-configuração falha e a aplicação não sobe.
2. **Gerenciador de transação Spring sobre esse `DataSource`** (`DataSourceTransactionManager` ou `JpaTransactionManager`) e `@Transactional` em quem chama `OutboxGravador.gravar` — sem transação ativa a gravação lança `IllegalTransactionStateException` e nada é gravado; é também o commit dessa transação que dispara o envio (`AFTER_COMMIT`).
3. **SQL Server com as tabelas da outbox criadas por script DDL** antes de subir a aplicação, com a estrutura do `schema.sql` da biblioteca (`../mensageria-outbox/core/src/main/resources/br/com/db1/poc/outbox/schema/sqlserver/schema.sql`), incluindo a coluna `sent_at` e o índice `IX_outbox_aggregate` — sem a tabela `outbox`, toda gravação falha e desfaz a transação do dado de negócio, e a recuperação registra erro na inicialização; sem o índice, a consulta de pendentes anteriores da chave, feita antes de cada publicação, perde o acesso por `(aggregate_key, seq)`.
4. **RabbitMQ acessível na inicialização, configurado por `spring.rabbitmq.*`** — o bean `outboxPublisher` declara o exchange na criação; sem broker a criação falha e a aplicação não sobe.
5. **Bean `outboxRabbitConnectionFactory`** (`com.rabbitmq.client.ConnectionFactory` com recuperação automática de conexão ligada), fornecido pela `OutboxRabbitConnectionFactoryConfiguration` de cada módulo — sem ele o `outboxPublisher` não é criado; sem a recuperação automática, o contrato de conexão da biblioteca não é cumprido.
6. **Fila quorum declarada pela aplicação para cada tipo de evento**, ligada ao exchange com a routing key igual ao `type` do envelope — a publicação usa `mandatory=true`; sem fila ligada, o broker devolve toda mensagem, o envio se repete indefinidamente e a chave fica retida.
7. **`com.rabbitmq:amqp-client` 5.37.0 no classpath da aplicação** — é a versão com que a biblioteca é compilada e testada; o BOM do Spring Boot gerencia versões anteriores (5.25.0 no 3.5.16, 5.30.0 no 4.1.1). Sem a fixação na aplicação, a publicação roda sobre uma versão do cliente não verificada.
8. **Contexto Spring Boot que publica o `ApplicationReadyEvent`** — sem ele a recuperação não roda: as linhas pendentes de execuções anteriores não são publicadas e retêm as posteriores da mesma `aggregateKey`.
9. **Relay por polling da biblioteca desligado na mesma aplicação** (`RelayFactory` não instanciado) — com os dois ativos, ambos competem pelas mesmas linhas pendentes, com políticas de envio diferentes.
10. **Padrão de log da aplicação com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`** — a integração preenche as chaves em cada tentativa de publicação e de consumo; sem elas no padrão de log, cada mensagem não é rastreável.
11. **Bean `InboxMessageHandler` e `inbox.consumer.queue`** — sem um dos dois a auto-configuração não cria o consumidor e a aplicação não consome.
12. **`DataSource` de H2 ou de SQL Server com a tabela `inbox` criada por script** antes de subir a aplicação (scripts em `../mensageria-outbox/core/src/main/resources/br/com/db1/poc/inbox/schema/<dialeto>/schema.sql`, aplicados por `InboxSchemaInstaller.install`) — com outro produto de banco a subida do consumidor falha; sem a tabela, toda mensagem falha e segue o retry e a DLQ.
13. **Fila principal e DLQ quorum declaradas pela aplicação** (a DLQ com `RabbitTopology.declareQuorumQueue(Channel, String)`) — o consumidor as confirma com `queueDeclarePassive` ao iniciar; se alguma não existir, a subida do consumidor falha.
14. **Driver JDBC do banco da inbox no classpath da aplicação** (H2 ou `mssql-jdbc`) — o `core` não o traz para o H2; sem ele o `DataSource` não abre conexão.
