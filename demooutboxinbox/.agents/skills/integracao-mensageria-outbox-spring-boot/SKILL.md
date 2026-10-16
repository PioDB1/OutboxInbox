---
name: integracao-mensageria-outbox-spring-boot
description: Como o demooutboxinbox (Spring Boot 4.1.1, Spring Data JPA, SQL Server) usa a outbox pelo artefato mensageria-outbox-spring-boot4. Use ao gravar, publicar ou recuperar eventos pela outbox, ao chamar OutboxGravador.gravar(envelope) em um serviço @Transactional, ao declarar a fila de um evento com RabbitTopology e o bean outboxRabbitConnectionFactory, ao criar as tabelas da outbox pelo script sql/outbox.sql, ao configurar spring.rabbitmq.* e outbox.relay.*, ao fixar o amqp-client e ao investigar disparo após o commit, publisher confirms, marcação de sent_at e reenvio dos pendentes na inicialização.
metadata:
  author: clovis-cli
  type: technical-skill
---

# Integração do mensageria-outbox com Spring Boot

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão: outro artefato ou linha do Spring Boot, novo passo no uso da outbox pelo demo, outra política de reenvio, outra fonte de configuração ou de esquema. Refactor técnico que preserva o how-to não pede atualização. Se esta skill disser X e o código do demo ou do artefato fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral do padrão

A biblioteca `br.com.db1.poc:mensageria-outbox:0.1.0-SNAPSHOT` não depende de framework. A integração com o Spring Boot é o artefato `br.com.db1.poc:mensageria-outbox-spring-boot4:0.1.0-SNAPSHOT`, mantido no repositório (`../mensageria-outbox-spring-boot4`, com o código comum em `../mensageria-outbox-spring-boot-shared`). Ao entrar no classpath, a auto-configuração `br.com.db1.poc.outbox.spring.OutboxAutoConfiguration` registra tudo o que a outbox precisa:

1. **Fábrica do RabbitMQ e exchange:** o bean `outboxRabbitConnectionFactory`, preenchido por `spring.rabbitmq.*` com recuperação automática ligada, e o `RabbitMessagePublisher` com confirms, que declara o exchange na inicialização.
2. **Gravação na transação:** o `OutboxGravador.gravar(envelope)` grava a linha da outbox na conexão JDBC da transação Spring em curso.
3. **Disparo assíncrono após o commit:** o `OutboxDespachante` repete o envio até o confirm do broker e marca `sent_at` em seguida, preservando a ordem por `aggregateKey`.
4. **Recuperação dos pendentes:** o `OutboxRecuperacao` entrega ao mesmo envio, na inicialização, as linhas com `sent_at IS NULL`.

O demo não tem código de integração próprio: ele declara a fila de cada evento que publica e chama `gravar` nos serviços de domínio. O comportamento completo do artefato está em `../mensageria-outbox-spring-boot4/README.md`, que prevalece sobre o resumo desta skill.

As tabelas da outbox são criadas fora da aplicação, pelo script `sql/outbox.sql` executado manualmente no banco; a auto-configuração nunca chama o `OutboxSchemaInstaller`.

O fluxo de referência é o diagrama `../mensageria-outbox/Outbox-Assync.puml`. O relay por polling da biblioteca (`RelayFactory`, `ClaimRelay`, `ReadPointRelay`, estratégias `claim`/`read-point`) não é instanciado. A garantia é **at-least-once**: a mesma mensagem pode ser publicada mais de uma vez (queda entre o confirm e a marcação de `sent_at`, ou envio após o commit concorrendo com a recuperação).

## Como aplicar

### 1. Dependências e configuração

- O `pom.xml` declara `spring-boot-starter-data-jpa`, `spring-boot-starter-amqp`, `mssql-jdbc` e `br.com.db1.poc:mensageria-outbox-spring-boot4:0.1.0-SNAPSHOT`, que traz o `mensageria-outbox`. O artefato declara o Spring como `provided`: valem as versões do Spring Boot 4.1.1 do demo.
- Os artefatos `br.com.db1.poc` são resolvidos do repositório `JFrog-Sisprime` quando publicados; localmente, vêm do `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install` executado na raiz do repositório.
- O `amqp-client` é fixado em 5.37.0 pela propriedade `rabbit-amqp-client.version` do `pom.xml`, a versão com que a biblioteca é construída e testada. Sem a propriedade, o `spring-boot-starter-parent` 4.1.1 resolve a 5.30.0.
- O `DataSource` é o auto-configurado pelo Spring Boot (`spring.datasource.*`); a transação é a do `JpaTransactionManager` auto-configurado.
- A configuração fica em `src/main/resources/application.properties`. Credenciais chegam por variáveis de ambiente (as de `../compose.yaml`: `MSSQL_SA_PASSWORD`, `MSSQL_PORT`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_AMQP_PORT`) referenciadas por placeholders, nunca por valores literais. `spring.rabbitmq.*` configura tanto o Spring AMQP quanto o `outboxRabbitConnectionFactory`.
- O `application.properties` importa o `.env` da raiz do repositório como fonte de configuração, com `spring.config.import=optional:file:../.env[.properties]`; o arquivo é lido no formato `.properties`. As variáveis de ambiente do processo têm precedência sobre os valores do `.env`. O caminho `../.env` é relativo ao diretório de trabalho: executada a partir de outro diretório (por exemplo, pela IDE), a aplicação não encontra o arquivo e depende das variáveis exportadas no ambiente.
- Os parâmetros de publicação usam as chaves `outbox.relay.*`, declaradas no `application.properties` com os padrões do artefato:

| Chave | Padrão | Uso |
|---|---|---|
| `enabled` | `false` | declarada explicitamente como `false`; o relay por polling da biblioteca fica desligado |
| `exchange` | `mensageria.outbox` | exchange declarado na inicialização e usado pelo `RabbitMessagePublisher` |
| `confirm-timeout` | `5s` | espera pelo confirm de cada publicação |
| `backoff.initial` | `200ms` | primeiro intervalo de reenvio de uma mensagem |
| `backoff.multiplier` | `2.0` | fator de crescimento do intervalo |
| `backoff.max` | `30s` | teto do intervalo |
| `batch-size` | `100` | linhas lidas por lote na recuperação da inicialização |

### 2. Esquema das tabelas

- As tabelas `outbox`, `outbox_lease` e `outbox_read_point` e os índices da `outbox` são criados pelo script `sql/outbox.sql`, executado manualmente no banco da aplicação antes de subi-la (por exemplo, `sqlcmd -S localhost,1433 -U sa -d master -i sql/outbox.sql`). O script é idempotente e separa os lotes com `GO`, interpretado pelo `sqlcmd` e pelo SSMS.
- O script tem a mesma estrutura do `schema.sql` da biblioteca, que o `SqlServerOutboxDialect` lê e escreve. Uma nova versão da biblioteca com outro `schema.sql` exige atualizar o script na mesma mudança.
- Não há Flyway nem Liquibase. As tabelas da outbox não são entidades JPA: são lidas e escritas somente pela API da biblioteca, e o `ddl-auto=update` do Hibernate não as toca.

### 3. Fila de cada evento

- Cada tipo de evento publicado precisa de uma fila ligada ao exchange com a routing key igual ao `type` do envelope, declarada na inicialização pelo domínio que publica o evento. A declaração usa `RabbitTopology.declareQuorumQueue` com o bean `outboxRabbitConnectionFactory` e recebe o bean `outboxPublisher` como parâmetro, para rodar depois da declaração do exchange (exemplo: `MovimentoMensageriaConfiguration`, que declara `movimentos.movimento-registrado` para `MovimentoRegistrado`):

```java
@Bean
FilaMovimentoRegistrado filaMovimentoRegistrado(ConnectionFactory outboxRabbitConnectionFactory,
		OutboxRelayProperties props, RabbitMessagePublisher outboxPublisher) throws IOException, TimeoutException {
	try (Connection connection = outboxRabbitConnectionFactory.newConnection();
			Channel channel = connection.createChannel()) {
		RabbitTopology.declareQuorumQueue(channel, FILA, props.exchange(), TIPO_EVENTO);
	}
	return new FilaMovimentoRegistrado();
}
```

- Declare as filas somente com `RabbitTopology.declareQuorumQueue`. Uma declaração da mesma fila por `Queue`/`Declarables` do Spring AMQP com argumentos diferentes de `x-queue-type=quorum`, `durable=true`, `exclusive=false` e `auto-delete=false` recebe `PRECONDITION_FAILED` do broker.

### 4. Gravação na transação

- O método de serviço que grava o dado de negócio é `@Transactional` e grava o envelope na outbox dentro da mesma transação com `OutboxGravador.gravar(envelope)`:

```java
@Transactional
public void registrar(...) {
	repository.saveAndFlush(entidade);
	outboxGravador.gravar(envelope);
}
```

- O `OutboxGravador` obtém a conexão com `DataSourceUtils.getConnection(dataSource)`: com JPA, é a conexão da sessão Hibernate que o `JpaTransactionManager` expõe para o `DataSource`. Use `saveAndFlush` (ou `entityManager.flush()`) antes de `gravar`, para que o identificador do dado de negócio exista e uma violação de restrição falhe antes da gravação da outbox. A atomicidade não depende do flush: qualquer falha antes do commit desfaz o dado de negócio e a linha da outbox.
- O `Envelope` chega pronto do domínio (`new Envelope(...)` ou `Envelope.novo(...)`); todos os textos, inclusive `traceId`, são obrigatórios e não vazios, e o payload é gravado como bytes.
- Chamado fora de transação, `gravar` lança `IllegalTransactionStateException` antes de tocar a conexão. Trate isso como erro de programação (falta de `@Transactional` em quem chama), nunca abrindo uma transação manual para contorná-lo. Uma `SQLException` na gravação chega como `DataAccessException` do Spring e desfaz a transação.
- Commit, rollback e fechamento da conexão são do Spring.

### 5. Envio após o commit e recuperação

- Depois do commit, a mensagem é publicada por uma thread própria do artefato, com o MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` em cada tentativa. Com o confirm positivo, `sent_at` é marcado em uma conexão própria, em autocommit; sem confirm, o envio é repetido com intervalo `min(backoff.max, backoff.initial × backoff.multiplier^(n-1))`, sem limite de tentativas e sem descarte.
- As mensagens do mesmo `aggregateKey` são publicadas uma de cada vez, em ordem de `seq`: cada uma só sai depois do confirm e da marcação de `sent_at` da anterior, e uma mensagem em falha retém as posteriores da mesma chave, enquanto as demais chaves seguem. Antes de publicar, o artefato consulta a outbox com `READUNCOMMITTED`; uma linha pendente anterior da mesma chave que ainda não chegou ao despachante (transação ainda aberta, ou linha que a recuperação ainda não leu) retém a chave até chegar ou deixar de existir.
- Uma falha ao marcar `sent_at` depois do confirm não republica a mensagem; a linha continua pendente, retém as posteriores do mesmo `aggregateKey` e é reenviada pela recuperação da próxima inicialização.
- Ao receber `ApplicationReadyEvent`, a recuperação lê as linhas pendentes em lotes de `outbox.relay.batch-size`, em uma thread própria, e espera cada lote ser confirmado e marcado antes de ler o próximo. Se uma linha do lote foi confirmada mas não marcada, a recuperação para; as pendentes restantes saem na próxima inicialização.
- Mensagens ainda em reenvio no encerramento da aplicação ficam pendentes no banco e são recuperadas na inicialização seguinte.

## Ferramentas e artefatos envolvidos

- Artefato `mensageria-outbox-spring-boot4`: uso e comportamento em `../mensageria-outbox-spring-boot4/README.md`; código comum em `../mensageria-outbox-spring-boot-shared/src/main/java/br/com/db1/poc/outbox/spring/` (`OutboxAutoConfiguration`, `OutboxRelayProperties`, `OutboxGravador`, `OutboxGravada`, `OutboxDespachante`, `OutboxRecuperacao`); `OutboxRabbitConnectionFactoryConfiguration` em `../mensageria-outbox-spring-boot4/src/main/java/`.
- Biblioteca `mensageria-outbox` (fonte em `../mensageria-outbox/src/main/java/br/com/db1/poc/outbox/`): `envelope.Envelope`, `rabbit.RabbitTopology`, `rabbit.RabbitMessagePublisher` e, por dentro do artefato, `write.OutboxWriter` e `dialect.sqlserver.SqlServerOutboxDialect`.
- Fluxo de disparo assíncrono: `../mensageria-outbox/Outbox-Assync.puml`. Esquema SQL: script `sql/outbox.sql` do demo, com a estrutura de `../mensageria-outbox/src/main/resources/br/com/db1/poc/outbox/schema/sqlserver/schema.sql`.
- Código do demo que usa a outbox: `MovimentoService` (gravação de `MovimentoRegistrado`) e `MovimentoMensageriaConfiguration` (fila `movimentos.movimento-registrado`).
- SLF4J com MDC para os logs de publicação; o padrão de log da aplicação (`logging.pattern.level`) inclui `%X{messageId}`, `%X{correlationId}`, `%X{aggregateKey}` e `%X{traceId}`.
- Ambiente local: `../compose.yaml` (SQL Server e RabbitMQ), com credenciais no `../.env`, não versionado.

## Restrições e armadilhas

- **Broker na inicialização.** O exchange é declarado durante a criação dos beans: sem RabbitMQ acessível, a aplicação não sobe.
- **Tabelas ausentes.** Sem o `sql/outbox.sql` executado, a aplicação sobe, toda gravação na outbox falha e desfaz a transação do dado de negócio, e a recuperação registra um erro ao ler os pendentes.
- **Duplicidade é esperada.** O envio após o commit e a recuperação podem publicar a mesma linha, assim como uma queda entre o confirm e a marcação de `sent_at`. O consumidor deduplica pelo `messageId`.
- **Chave retida.** Uma linha pendente anterior retém as mensagens posteriores do mesmo `aggregateKey` enquanto existir: uma transação longa ainda aberta, ou uma linha confirmada cuja marcação de `sent_at` falhou, seguram a chave até o commit ou rollback, ou até a recuperação da próxima inicialização.
- **Fila obrigatória.** A publicação usa `mandatory=true`: sem fila ligada à routing key, o broker devolve a mensagem e o reenvio se repete indefinidamente.
- **Evento fora de transação.** O envio após o commit depende de `@TransactionalEventListener`; `gravar` fora de transação é recusado.
- **Memória durante indisponibilidade do broker.** As mensagens em reenvio ficam no agendador até o confirm; o estado durável é sempre a linha da outbox, e a aplicação continua gravando normalmente.
- **Métricas e limpeza do relay ausentes.** `outbox.lag.seconds`, `outbox.pending`, `outbox.publish.retries` e a retenção da outbox pertencem ao relay por polling e não existem neste caminho; as linhas enviadas permanecem na tabela.
- **Relay por polling.** Não instancie `RelayFactory`, `ClaimRelay` nem `ReadPointRelay` junto do artefato: eles competiriam pelas mesmas linhas pendentes com outra política de envio.
