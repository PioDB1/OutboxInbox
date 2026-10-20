---
name: functional-map
description: Mapa do domínio único do demooutboxinbox — Pagamentos, produtor de PagamentoRecebido e MovimentoRegistrado sobre a biblioteca mensageria-outbox, com Spring Data JPA, SQL Server único e disparo assíncrono após o commit — com evidências, regras inferidas, dependências técnicas e confiança; sem lacunas abertas.
metadata:
  author: clovis-cli
  responsibility: "Map of identification of the business domains (bounded contexts), their boundaries, dependencies and suggested implementation order. Index of domains for skill generation and the spec-driven flow; it does not detail business rules nor duplicate the cross-cutting decisions, which live in the discovery-answers.md."
---

# Mapa funcional

O `demooutboxinbox` é um scaffold Spring Boot 4.1.1/Java 21 que demonstra o lado produtor do padrão outbox com um único domínio de negócio, Pagamentos. Consumo, inbox e reconciliação estão fora do escopo (`.agents/context/discovery-answers.md`, D1). As decisões transversais (stack, persistência, disparo da publicação, testes, documentação) estão em `.agents/context/discovery-answers.md`; o efeito de cada uma está refletido no bloco abaixo.

A mecânica de outbox vem da biblioteca `mensageria-outbox` e entra pela auto-configuração do artefato `mensageria-outbox-spring-boot4`, descrita nas dependências técnicas de Pagamentos.

## 1. Pagamentos (`pagamentos`)

**Objetivo de negócio:** receber a solicitação de pagamento, registrá-lo e publicar o fato `PagamentoRecebido`, de modo que o pagamento e a mensagem existam juntos ou não existam.

**Evidência no código:**
- `pom.xml` — dependência `br.com.db1.poc:mensageria-outbox-spring-boot4:0.3.0-SNAPSHOT`, `spring-boot-starter-webmvc` (endpoints de movimentação), `spring-boot-starter-amqp`, `spring-boot-starter-actuator` e `mssql-jdbc`.
- `../compose.yaml` — cabeçalho "Ambiente local do relay de Pagamentos".
- `../mensageria-outbox/README.md` — exemplo de uso do hospedeiro com o tipo `PagamentoRecebido` e o exchange `mensageria.outbox`.
- `../mensageria-outbox/Outbox-Assync.puml` — fluxo de disparo assíncrono após a gravação e de reenvio dos pendentes na inicialização.
- `src/main/java/br/com/db1/demooutboxinbox/movimento/` — `POST /movimentos/inclusao` (movimentação e `MovimentoRegistrado` na mesma transação), `POST /movimentos/inclusao_sem_evento` (só a movimentação) e `POST /movimentos/inclusao_dupla` (inclusão atômica de dois movimentos com eventos, com suporte a falha forçada por posição), com a fila `movimentos.movimento-registrado` declarada na inicialização.

**Estado da implementação:** a inclusão de movimentação está implementada. O registro de pagamento (`POST /pagamentos`, tabela `pagamento`, evento `PagamentoRecebido` e fila `pagamentos.pagamento-recebido`) é escopo do domínio descrito na skill, sem código, a implementar por spec.

**Evidência na fonte externa:** `../mensageria-outbox/spec-caso-a-outbox-inbox.md` — §5.1 (tabelas `pagamento` e `outbox`), §6.2 (contrato `PagamentoRecebido` v1), RF-A1 e cenários "Fluxo feliz" e "Falha na gravação do pagamento" (§11).

**Dependências entre domínios:** nenhuma.

**Regras inferidas:**
- `pagamento` e a linha da `outbox` são gravados na mesma transação local JPA, no único banco SQL Server da aplicação; falha na transação não deixa nenhum dos dois.
- Status inicial `RECEBIDO`; valor com duas casas decimais; `cooperadoId` numérico.
- `PagamentoRecebido` v1 carrega `pagamentoId`, `cooperadoId` e `valor`, com `pagamentoId` como `aggregateKey`; a publicação usa routing key igual ao `type`.
- `POST /movimentos/inclusao` grava a movimentação e a mensagem `MovimentoRegistrado` v1 (`movimentoId` e `tipo`, com o `id` da movimentação como `aggregateKey`) na mesma transação, publicada na fila `movimentos.movimento-registrado`; `POST /movimentos/inclusao_sem_evento` grava só a movimentação; `POST /movimentos/inclusao_dupla` grava dois movimentos e dois eventos na mesma transação, preservando a ordem de gravação na outbox.
- Depois do commit, a aplicação dispara a publicação de forma assíncrona; a mensagem só é considerada enviada depois do confirm do broker, quando a linha da outbox recebe `sent_at`. Até o confirm, o envio é repetido.
- Na inicialização, a aplicação consulta as linhas pendentes da outbox (`sent_at` nulo) e as enfileira para o mesmo envio assíncrono, cobrindo publicações interrompidas por queda da aplicação ou do broker.
- Publicação at-least-once: a mesma mensagem pode ser publicada mais de uma vez (por exemplo, queda entre o confirm e a marcação de `sent_at`).
- Não há consumidor no demo: a mensagem publicada fica na fila do broker.

**Dependências externas relevantes:**
- `mensageria-outbox-spring-boot4` (0.3.0-SNAPSHOT, repositório `JFrog-Sisprime` ou Maven local) — auto-configuração com `OutboxGravador`, envio após o commit e recuperação dos pendentes, sobre o `mensageria-outbox-core` (`Envelope`, `OutboxWriter`, `SqlServerOutboxDialect`, `RabbitMessagePublisher` e `RabbitTopology`). O relay por polling (`RelayFactory`, estratégias `claim` e `read-point`) não é usado; a publicação segue o disparo assíncrono do `Outbox-Assync.puml`, com `outbox.relay.enabled=false`.
- Spring Data JPA (Hibernate), pelo `spring-boot-starter-data-jpa` — persistência do `pagamento` e transação local compartilhada com o `OutboxWriter`.
- SQL Server (`mssql-jdbc`) — banco único com `pagamento`, `movimento`, `outbox`, `outbox_lease` e `outbox_read_point`, as quatro últimas criadas pelo script manual `sql/outbox.sql` (a `movimento` também pelo Hibernate, quando o script não foi executado); sem ferramenta de migração (sem Flyway ou Liquibase).
- RabbitMQ (`amqp-client` 5.37.0, fixado no `pom.xml`; `spring-boot-starter-amqp`) — publisher confirms, exchange `direct` `mensageria.outbox`.
- Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `spring-boot-starter-actuator`) e Micrometer.

**Dependências técnicas do domínio:**
- Artefato `mensageria-outbox-spring-boot4` no classpath (exchange, `OutboxGravador` na conexão JDBC da transação JPA, `RabbitMessagePublisher` sobre o `outboxRabbitConnectionFactory`) — sem ele a outbox não é gravada nem publicada.
- Tabelas da outbox criadas pelo script `sql/outbox.sql`, executado manualmente no banco — sem elas toda gravação na outbox falha e desfaz a transação do dado de negócio.
- Disparo assíncrono após o commit e recuperação de pendentes na inicialização (`Outbox-Assync.puml`) — sem ele o pagamento é gravado e o evento nunca é publicado.
- Ambiente local do `../compose.yaml` (SQL Server e RabbitMQ) com credenciais no `.env` — sem ele a aplicação não tem banco nem broker, e o teste `contextLoads` falha.
- Logs com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` — sem elas a publicação de um pagamento ou de uma movimentação não é rastreável.

**Confiança:** alta para o escopo e o fluxo de gravação e publicação (decisões humanas g1, g4 e g6, contrato da biblioteca e spec §5.1/§6.2); média para a semântica de reenvio do disparo assíncrono, que vem do diagrama `Outbox-Assync.puml` e não de requisito escrito.

## Ordem de implementação sugerida

1. `pagamentos` — produtor dos eventos, sobre o artefato `mensageria-outbox-spring-boot4`.
