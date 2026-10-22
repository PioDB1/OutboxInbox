---
name: mensageria-outbox-spring-boot-shared
description: Contrato de negócio da integração Spring Boot da outbox e do consumidor (inbox), código comum dos artefatos mensageria-outbox-spring-boot3 e 4. Use ao alterar OutboxGravador.gravar e a gravação atômica na transação, OutboxDespachante e o envio após o commit, publisher confirm e sent_at, retry com backoff, ordem por aggregateKey, OutboxRecuperacao e a recuperação de pendentes no ApplicationReadyEvent, at-least-once e duplicidades, OutboxRelayProperties e as chaves outbox.relay.*, OutboxAutoConfiguration e seus beans, ou InboxAutoConfiguration, InboxProperties e as chaves inbox.consumer.*, SpringInboxTransactionExecutor, InboxConsumerLifecycle e a detecção do dialeto da inbox.
metadata:
  author: clovis-cli
  type: domain-skill
---

# Integração Spring Boot da outbox (código comum)

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão (novo critério de envio, outra semântica de ordem, de recuperação ou de configuração, novo bean ou nova chave). Refactor técnico que preserva a regra (renomear, extrair, reorganizar classes) não pede atualização. Se a skill disser X e o código ou o estado do projeto fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

Esta pasta guarda o código-fonte comum das auto-configurações que integram a biblioteca `mensageria-outbox-core` (lado produtor, pacote `outbox`, e lado consumidor, pacote `inbox`) a aplicações Spring Boot. Não é módulo Maven: os módulos `mensageria-outbox-spring-boot3` (Spring Boot 3.x) e `mensageria-outbox-spring-boot4` (Spring Boot 4.x) acrescentam `src/main/java` e `src/test/java` daqui às próprias fontes, e o contrato descrito abaixo vale igual nos dois artefatos.

A integração entrega quatro comportamentos:

1. **Gravação atômica:** a aplicação grava o dado de negócio e a linha da outbox na mesma transação local do seu banco, pelo `OutboxGravador`.
2. **Envio após o commit:** cada linha confirmada é publicada de forma assíncrona no RabbitMQ, com publisher confirm, retry sem limite e ordem por `aggregateKey`.
3. **Recuperação na inicialização:** as linhas que ficaram pendentes em execuções anteriores são publicadas pelo mesmo envio.
4. **Consumo:** com `inbox.consumer.queue` e um bean `InboxMessageHandler`, o `InboxConsumer` da biblioteca sobe com o ciclo de vida do Spring e executa o tratador na transação Spring da aplicação, com o registro na inbox pela mesma conexão (regras R31 a R37).

### Fronteira

- **Pertence a este contrato:** a gravação na transação Spring, o evento `OutboxGravada`, o despacho após o commit, a ordem por `aggregateKey`, a recuperação, as chaves `outbox.relay.*` lidas aqui e o registro dos beans em `OutboxAutoConfiguration`; do lado consumidor, a `InboxAutoConfiguration`, as chaves `inbox.consumer.*`, o executor transacional Spring, o ciclo de vida do consumidor e a detecção do dialeto da inbox.
- **Pertence à biblioteca** (`../mensageria-outbox/core/src/main/java/br/com/db1/poc/`): em `outbox/`, o `OutboxWriter`, as consultas SQL do `SqlServerOutboxDialect`, o `RabbitMessagePublisher`, o `BackoffPolicy` e o relay por polling; em `inbox/`, o `InboxConsumer`, o `InboxConfig`, a porta `MessageSource` e o adaptador `RabbitMessageSource`, os dialetos `H2InboxDialect` e `SqlServerInboxDialect`, o `InboxSchemaInstaller`, o `InboxMetrics`, o `BlockedKeys`, o retry com backoff e a DLQ; em `shared/`, o código comum à outbox e à inbox, do qual esta pasta importa `br.com.db1.poc.shared.envelope.Envelope` e `br.com.db1.poc.shared.rabbit.RabbitTopology`.
- **Pertence a cada módulo:** o bean `outboxRabbitConnectionFactory` (classe `OutboxRabbitConnectionFactoryConfiguration`, que depende do pacote do `RabbitProperties` de cada linha) e o arquivo `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **Pertence à aplicação:** o dado de negócio e o payload, a declaração das filas (as de destino da outbox e, no consumo, a fila principal e a DLQ), a criação das tabelas da outbox e da `inbox` por script, o bean `InboxMessageHandler` com o efeito de negócio e a configuração de `spring.datasource.*` e `spring.rabbitmq.*`.

## Regras de negócio

Numeração própria desta skill (R1, R2, ...).

### Gravação atômica

- **R1.** `OutboxGravador.gravar(envelope)` é o ponto de entrada da aplicação. Grava pela conexão que o gerenciador de transação expõe para o `DataSource` (`DataSourceUtils.getConnection`): a do `DataSourceTransactionManager` ou, com JPA, a da sessão Hibernate do `JpaTransactionManager`. Com o commit, o dado de negócio e a linha da outbox existem juntos; com o rollback, nenhum dos dois existe e nada é enviado.
- **R2.** A gravação exige transação Spring ativa (`TransactionSynchronizationManager.isActualTransactionActive()`). Fora dela, `gravar` lança `IllegalTransactionStateException`: é erro de programação (falta de `@Transactional` em quem chama). Uma conexão que não pertence à transação chega em autocommit, e o `OutboxWriter` a recusa com `IllegalStateException`.
- **R3.** `gravar` devolve o `seq` atribuído pelo banco e traduz `SQLException` para `DataAccessException` (`SQLExceptionSubclassTranslator`, com `UncategorizedSQLException` quando a tradução não se aplica), uma exceção de runtime que desfaz a transação de quem chama pelas regras padrão do `@Transactional`.
- **R4.** Com JPA, quem chama grava o dado de negócio antes, com `saveAndFlush` ou `flush`, para que uma violação de restrição falhe antes da gravação da outbox.
- **R5.** Depois da gravação, `gravar` publica o evento de aplicação `OutboxGravada(seq, envelope)`; é ele que liga a gravação ao envio após o commit.
- **R6.** A auto-configuração não cria as tabelas da outbox nem chama o `OutboxSchemaInstaller`: as tabelas vêm de script DDL executado no banco antes de subir a aplicação, com a estrutura do `schema.sql` da biblioteca.

### Envio após o commit

- **R7.** `OutboxDespachante.aoGravar` é `@TransactionalEventListener(phase = AFTER_COMMIT)`: só linhas de transações confirmadas são despachadas. O método entrega a linha a um agendador próprio de thread única (prefixo `outbox-despacho-`) e retorna; a thread que confirmou a transação não publica no broker, e a resposta da aplicação não depende da disponibilidade do broker.
- **R8.** O agendador tem uma única thread, que guarda todo o estado das chaves: o `publish` do `RabbitMessagePublisher` é sincronizado sobre um único canal, e threads adicionais só ficariam bloqueadas nele.
- **R9.** A linha só é considerada enviada depois do **confirm positivo** do broker, sem devolução por `basic.return` (`mandatory=true`), com espera de até `outbox.relay.confirm-timeout` por mensagem. Só então `sent_at` é marcado, em conexão própria em autocommit, fora de qualquer transação JPA.
- **R10.** Falha de publicação (broker inacessível, confirm ausente no prazo, confirm negativo, mensagem devolvida) ou da consulta à outbox que antecede a publicação (R14) é retentada com intervalo `min(max, initial × multiplier^(n-1))`, em que `n` é o número de falhas consecutivas da chave, **sem limite de tentativas e sem descarte**. Um envio bem-sucedido zera a contagem da chave.
- **R11.** Cada tentativa roda com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`.
- **R12.** O bean `outboxPublisher` declara o exchange (`outbox.relay.exchange`, `direct` e durável) na criação: o RabbitMQ precisa estar disponível na inicialização da aplicação. As filas são declaradas pela aplicação, uma fila quorum por tipo de evento ligada à routing key igual ao `type` do envelope; sem fila ligada, cada envio é devolvido e se repete indefinidamente, retendo a chave.

### Ordem por `aggregateKey`

- **R13.** As mensagens da mesma `aggregateKey` são publicadas uma de cada vez, em ordem de `seq`; cada uma só sai depois do confirm e da marcação de `sent_at` da anterior. Uma mensagem em falha ou em espera retém as posteriores da mesma chave, enquanto as demais chaves seguem. Não existe ordem global entre chaves.
- **R14.** Antes de cada publicação, o despachante consulta a outbox com `hasPendingBetween` (`READUNCOMMITTED`, pelo índice `IX_outbox_aggregate`) duas vezes:
  1. se a própria linha já está marcada como enviada, a publicação é descartada e a linha conta como concluída;
  2. se existe linha pendente da mesma chave com `seq` entre o piso e o `seq` da linha, a chave fica retida. O piso é o último `seq` da chave enviado nesta execução, mantido em memória para até 10.000 chaves (as menos usadas saem primeiro); sem ele, o piso é zero.
- **R15.** A leitura `READUNCOMMITTED` enxerga linhas de transações ainda não confirmadas e linhas de execuções anteriores que a recuperação ainda não leu: as duas retêm a chave. A chave retida é verificada de novo com o mesmo intervalo crescente de R10, ou imediatamente quando uma linha da chave chega ao despachante.
- **R16.** Uma linha pendente anterior retém a sua chave enquanto existir: uma transação longa ainda aberta segura as mensagens posteriores da mesma chave até o commit ou o rollback.

### Recuperação na inicialização

- **R17.** `OutboxRecuperacao` reage ao `ApplicationReadyEvent` em thread própria (`outbox-recuperacao`), sem bloquear a inicialização. Lê as linhas com `sent_at IS NULL` em lotes de `outbox.relay.batch-size`, em ordem de `seq`, e entrega cada uma ao despachante; só lê o lote seguinte depois de todas as linhas do lote atual serem confirmadas e marcadas.
- **R18.** A leitura do lote usa `UPDLOCK, READPAST, ROWLOCK` e confirma a sua transação antes do despacho, porque os bloqueios mantidos até o fim da transação impediriam a marcação de `sent_at` pelo despachante.
- **R19.** Quando uma linha do lote é confirmada pelo broker e a marcação de `sent_at` falha, a recuperação para sem esperar o restante do lote, e as pendentes restantes ficam para a próxima inicialização. Falha na leitura das pendentes encerra a recuperação com log de erro.

### Entrega at-least-once

- **R20.** A entrega é **at-least-once**; quem consome deduplica pelo `messageId`. Uma mensagem é publicada mais de uma vez quando:
  - o broker confirma e a marcação de `sent_at` falha, ou a aplicação para entre o confirm e a marcação: a linha continua pendente, não é republicada na hora e sai de novo pela recuperação da próxima inicialização; até lá, retém as posteriores da mesma chave;
  - mais de uma instância da aplicação usa o mesmo banco: cada instância despacha e recupera as pendentes sem coordenação entre elas (não há lease nesta integração).
- **R21.** Dentro de uma única instância, uma linha entregue duas vezes ao despachante (após o commit e pela recuperação) é publicada uma vez: enquanto a linha está em andamento, a segunda entrega recebe o mesmo resultado da primeira, e depois de concluída a consulta de R14 descarta a linha já marcada.
- **R22.** No encerramento da aplicação, o agendador para e as linhas em andamento permanecem pendentes no banco, para a recuperação da próxima inicialização.

### Configuração `outbox.relay.*`

- **R23.** `OutboxRelayProperties` lê as chaves abaixo, todas opcionais, com o mesmo prefixo e os mesmos padrões do relay por polling da biblioteca:

| Chave | Padrão | Uso |
|---|---|---|
| `outbox.relay.exchange` | `mensageria.outbox` | exchange de publicação, declarado na inicialização |
| `outbox.relay.confirm-timeout` | `5s` | espera pelo confirm de cada mensagem |
| `outbox.relay.backoff.initial` | `200ms` | primeiro intervalo entre tentativas de uma chave |
| `outbox.relay.backoff.multiplier` | `2.0` | fator de crescimento do intervalo |
| `outbox.relay.backoff.max` | `30s` | teto do intervalo |
| `outbox.relay.batch-size` | `100` | linhas por lote da recuperação |

- **R24.** As demais chaves `outbox.relay.*` da biblioteca (`enabled`, `strategy`, `lease.*`, `retention.*`, `poll-interval`) não são lidas e não têm efeito na integração; `outbox.relay.enabled=false` não desliga a auto-configuração.

### Coexistência com o relay por polling

- **R25.** A auto-configuração não instancia o relay por polling da biblioteca (`RelayFactory`, estratégias claim e ponto de leitura), e a aplicação nunca usa os dois caminhos juntos: eles competiriam pelas mesmas linhas pendentes com políticas de envio diferentes. A aplicação que precisa do relay por polling não usa esta auto-configuração.
- **R26.** A integração não executa a retenção da outbox, não registra as métricas Micrometer de publicação e não usa lease; essas funções pertencem ao relay por polling. As linhas enviadas permanecem na tabela.

### Registro de beans

- **R27.** `OutboxAutoConfiguration` (`@AutoConfiguration`) registra, todos com `@ConditionalOnMissingBean`: `outboxDialect` (`SqlServerOutboxDialect`), `outboxWriter` (`OutboxWriter`), `outboxPublisher` (`RabbitMessagePublisher`, fechado no encerramento), `outboxGravador`, `outboxDespachante` e `outboxRecuperacao`. Todo bean novo da integração segue a mesma regra, para que a aplicação possa substituí-lo.
- **R28.** O `outboxPublisher` usa o bean `outboxRabbitConnectionFactory` pelo qualificador; esse bean vem da `OutboxRabbitConnectionFactoryConfiguration` de cada módulo, importada por `@Import`, que existe com o mesmo nome e pacote (`br.com.db1.poc.outbox.spring`) nos dois módulos. O estado desse bean em relação a `@ConditionalOnMissingBean` está registrado em `.agents/context/pendencias.md` de cada módulo.

### Compatibilidade entre as linhas do Spring Boot

- **R29.** Todo código desta pasta compila e passa nos testes dos dois módulos: usa apenas APIs disponíveis no Spring Boot 3.x e no 4.x e no Java 17 (`--release 17`; o enforcer recusa bytecode acima de 17). O que difere entre as linhas fica fora desta pasta, em uma classe de mesmo nome e pacote em cada módulo.
- **R30.** Os testes desta pasta (`OutboxAutoConfigurationTest`, `OutboxDespachanteTest`, `InboxAutoConfigurationTest` e `SpringInboxTransactionExecutorTest`, sufixo `*Test`) rodam nos dois módulos, sem broker: as auto-configurações sobem com `ApplicationContextRunner`, o despachante é exercitado com outbox, banco e publicador simulados em memória, e o consumidor sobe com H2 em memória, `DataSourceTransactionManager` e uma `MessageSource` simulada.

### Consumidor (inbox)

- **R31.** `InboxAutoConfiguration` (`@AutoConfiguration`) é separada da `OutboxAutoConfiguration` e registrada no `AutoConfiguration.imports` de cada módulo; cada lado sobe só quando a aplicação o usa. Ela importa a `OutboxRabbitConnectionFactoryConfiguration` por `@Import`, e o bean `outboxRabbitConnectionFactory` é o mesmo da outbox, registrado uma vez. Todo bean dela é `@ConditionalOnMissingBean`.
- **R32.** O `InboxConsumer` só é criado com `inbox.consumer.queue` definida e um bean `InboxMessageHandler` na aplicação. Sem um dos dois nenhum bean do consumidor é criado e o contexto sobe, inclusive uma aplicação só com a outbox.
- **R33.** A auto-configuração registra, cada um substituível pela aplicação: o `InboxDialect`, o `InboxTransactionExecutor`, o `InboxMetrics`, o `BlockedKeys` (`InMemoryBlockedKeys`), o `MessageSource` (`RabbitMessageSource` sobre o `outboxRabbitConnectionFactory` e `inbox.consumer.confirm-timeout`) e o `InboxConsumer`, cujo início e fim o `InboxConsumerLifecycle` (`SmartLifecycle`) conduz com o ciclo de vida do Spring. O `InboxMetrics` usa o `MeterRegistry` da aplicação, ou um registro simples na falta dele.
- **R34.** O dialeto é escolhido pelo `DatabaseMetaData.getDatabaseProductName` do `DataSource`: `Microsoft SQL Server` usa `SqlServerInboxDialect` e `H2` usa `H2InboxDialect`. Com outro produto, a subida do consumidor falha com mensagem que nomeia o produto detectado e os dois suportados.
- **R35.** O `SpringInboxTransactionExecutor` executa o trabalho num `TransactionTemplate` do `PlatformTransactionManager` da aplicação e entrega a conexão de `DataSourceUtils.getConnection(dataSource)`: o tratador roda dentro da transação Spring, e o registro na inbox, o `JdbcTemplate` ou a sessão JPA participam da mesma transação. Exceção do tratador desfaz a transação; o ack só sai depois do commit.
- **R36.** `InboxProperties` lê as chaves abaixo (`Duration` nas durações), com os mesmos padrões do `InboxConfig.fromProperties` da biblioteca, que recusa os valores inválidos com `IllegalArgumentException`:

| Chave | Padrão | Uso |
|---|---|---|
| `inbox.consumer.queue` | obrigatória | fila principal (quorum, durável, declarada pela aplicação) |
| `inbox.consumer.dead-letter-queue` | `<queue>.dlq` | DLQ (quorum, durável, declarada pela aplicação) |
| `inbox.consumer.max-attempts` | `5` | tentativas de processamento por mensagem antes da DLQ (a primeira conta) |
| `inbox.consumer.backoff.initial` | `200ms` | intervalo depois da primeira falha |
| `inbox.consumer.backoff.multiplier` | `2.0` | fator de crescimento do intervalo (finito e maior ou igual a 1,0) |
| `inbox.consumer.backoff.max` | `30s` | teto do intervalo |
| `inbox.consumer.max-in-flight` | `50` | mensagens sem ack, igual ao `basicQos` |
| `inbox.consumer.workers` | `8` | threads de trabalho das filas por chave |
| `inbox.consumer.confirm-timeout` | `5s` | espera do confirm da publicação na DLQ |

- **R37.** A auto-configuração não cria a tabela `inbox` nem chama o `InboxSchemaInstaller`: a tabela vem do script do dialeto, executado antes de a aplicação subir. A fila principal e a DLQ são declaradas pela aplicação; ao iniciar, o consumidor as confirma com `queueDeclarePassive` e a subida falha se alguma não existir. O bloqueio de chave da DLQ vive só na memória do processo (P7 de `../mensageria-outbox/.agents/context/pendencias.md`).

## Entidades e dados

- **`OutboxGravada(long seq, Envelope envelope)`** — evento de aplicação de uma linha gravada, tratado depois do commit.
- **Resultado de `OutboxDespachante.despachar(seq, envelope)`** — `CompletableFuture<Boolean>`: `true` quando a linha foi publicada e marcada, ou já estava marcada; `false` quando o broker confirmou e a marcação falhou; concluído com exceção quando a aplicação encerra antes do confirm. Nos dois últimos casos a linha permanece pendente.
- **Tabela `inbox`** — `message_id` (chave primária) e `consumed_at`, no banco da aplicação consumidora, em H2 ou SQL Server; estrutura em `../mensageria-outbox/.agents/specs/003-mensageria-outbox-nucleo-inbox/data-model.md`.
- **Tabela `outbox` (SQL Server)** — a integração usa `seq`, `aggregate_key` e `sent_at` (pendente com `sent_at` nulo, enviada com `sent_at` preenchido por `SYSUTCDATETIME()`) e o índice `IX_outbox_aggregate (aggregate_key, seq)`. As tabelas `outbox_lease` e `outbox_read_point` vêm no mesmo script e pertencem ao relay por polling.

## Integrações e dependências externas

- **SQL Server:** banco da aplicação, que contém a outbox; o único dialeto da outbox é o `SqlServerOutboxDialect`. A inbox aceita SQL Server ou H2.
- **RabbitMQ:** broker com publisher confirms, exchange `direct` e filas quorum declaradas pela aplicação.
- **Biblioteca `mensageria-outbox-core`:** envelope, gravação, consultas da outbox, publicador e política de backoff; do lado consumidor, o `InboxConsumer`, os dialetos da inbox e o adaptador RabbitMQ.
- **Spring Framework e Spring Boot:** transação, eventos de aplicação, `ApplicationReadyEvent`, `@ConfigurationProperties` e auto-configuração.

As precondições técnicas — o que precisa existir para o contrato acima funcionar e o efeito da ausência de cada uma — estão em [references/technical-dependencies.md](references/technical-dependencies.md).

## Documentação completa

- `README.md` desta pasta: o que é compartilhado e como entra nos módulos.
- `../mensageria-outbox-spring-boot3/README.md` e `../mensageria-outbox-spring-boot4/README.md`: uso pela aplicação, configuração, comportamento e restrições.
- `src/main/java/br/com/db1/poc/outbox/spring/`: o código-fonte é a fonte da verdade do comportamento implementado.
- `../mensageria-outbox/README.md`: contrato da biblioteca (envelope, mensagem publicada, relay por polling).
