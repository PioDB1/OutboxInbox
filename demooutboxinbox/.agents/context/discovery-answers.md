---
name: discovery-answers
description: Memória da descoberta funcional do demooutboxinbox — demo Spring Boot 4.1.1/Java 21 só do lado produtor (Pagamentos publica PagamentoRecebido pela biblioteca mensageria-outbox), com banco SQL Server único, Spring Data JPA, disparo assíncrono após o commit e recuperação de pendentes na inicialização e Swagger UI como única documentação da API; inclui restrições, decisões transversais com destino e o registro das decisões humanas.
metadata:
  author: clovis-cli
  responsibility: "Durable memory of the functional-discovery context and decisions: objective, scope, declared restrictions, validated cross-cutting decisions (with destination), documentation forms to maintain and the log of human decisions resolved in the gap loop. Source re-read by the following stages; its restrictions take precedence over later inferences."
---

# Respostas da descoberta

## Objetivo e escopo

- **Objetivo:** POC de demonstração do lado produtor do padrão outbox. O `demooutboxinbox` é uma aplicação Spring Boot que consome a biblioteca `br.com.db1.poc:mensageria-outbox:0.1.0-SNAPSHOT` (projeto irmão `../mensageria-outbox`) e mostra, em uma aplicação executável, que o pagamento e a mensagem `PagamentoRecebido` existem juntos ou não existem, com publicação assíncrona disparada após o commit.
- **Tipo de trabalho:** manutenção de um scaffold existente (gerado pelo Spring Initializr, com a classe `DemooutboxinboxApplication` e o teste `contextLoads`).
- **Escopo:** o projeto inteiro (`demooutboxinbox/`), limitado ao domínio Pagamentos.
- **Fora de escopo:** consumo de mensagens e inbox (Extrato, dedup, ack manual, retry/DLQ do consumidor), reconciliação das invariantes A1 a A4, testes automatizados, ferramenta de migração de banco e documentação além do Swagger UI.
- **Referência de negócio no repositório:** `../mensageria-outbox/spec-caso-a-outbox-inbox.md` (Caso A: §5.1, §6.2 e RF-A1 para Pagamentos), `../mensageria-outbox/README.md` (contrato de uso da biblioteca pelo hospedeiro) e `../mensageria-outbox/Outbox-Assync.puml` (fluxo de disparo assíncrono).

## Restrições declaradas

O usuário não declarou restrições no questionário da sessão. As decisões do laço de lacunas (abaixo) delimitam o escopo e a stack.

Restrições impostas pelo contrato da biblioteca `mensageria-outbox` (`../mensageria-outbox/README.md`), válidas para qualquer uso dela no demo:

| Restrição | Origem | Destino |
|---|---|---|
| `OutboxWriter.insert` grava na conexão da transação aberta pela aplicação (`autoCommit=false`) e lança `IllegalStateException` com a conexão em `autoCommit`; commit, rollback e fechamento são da aplicação | README da biblioteca, "Uso pelo hospedeiro" | Skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| As tabelas `outbox`, `outbox_lease` e `outbox_read_point` seguem a estrutura do `schema.sql` da biblioteca, lida e escrita pelo `SqlServerOutboxDialect`; a coluna `sent_at` marca a linha enviada | README e `schema.sql` da biblioteca | Skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| A publicação no RabbitMQ usa `RabbitMessagePublisher` (publisher confirms, exchange `direct`, routing key igual ao `type`, `mandatory=true`) sobre um `ConnectionFactory` do `amqp-client` configurado pelo hospedeiro | README da biblioteca | Skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| Credenciais de banco e broker chegam por variáveis de ambiente ou `.env`, nunca versionadas | README; `../compose.yaml`; `../.gitignore` ignora `.env` | Convenção em `AGENTS.md` |

## Decisões transversais validadas

| Id | Decisão | Origem | Destino |
|---|---|---|---|
| E1 | Java 21 e Spring Boot 4.1.1 | `pom.xml`: `spring-boot-starter-parent` 4.1.1 e `java.version` 21 | Convenção em `AGENTS.md` |
| E2 | Build Maven com wrapper próprio (`mvnw`/`mvnw.cmd`); o projeto é independente do agregador `outbox-inbox-poc` da raiz, que lista `mensageria-outbox`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` | `mvnw`, `.mvn/wrapper/`; `../pom.xml` `<modules>` | Convenção em `AGENTS.md` |
| E3 | A biblioteca `mensageria-outbox` 0.1.0-SNAPSHOT entra pelo artefato `mensageria-outbox-spring-boot4` (D10), ambos resolvidos do repositório `JFrog-Sisprime` (`https://artifactory.sisprimedobrasil.net:443/artifactory/dependencies-maven/`) ou do repositório Maven local | `pom.xml` (`<dependency>` e `<repositories>`); README da biblioteca e do artefato | Convenção em `AGENTS.md`; skill técnica `integracao-mensageria-outbox-spring-boot` |
| E4 | RabbitMQ pelo `spring-boot-starter-amqp`, com a publicação feita pelo `RabbitMessagePublisher` da biblioteca | `pom.xml`; README da biblioteca | Convenção em `AGENTS.md`; domínio Pagamentos |
| E5 | API HTTP pelo `spring-boot-starter-webmvc` | `pom.xml` | Domínio Pagamentos (endpoint de solicitação) |
| E6 | Observabilidade pelo `spring-boot-starter-actuator` (Micrometer); os logs de publicação incluem as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` | `pom.xml`; README da biblioteca, "Métricas e logs" | Convenção em `AGENTS.md` |
| E7 | Lombok como processador de anotações na compilação principal e de testes | `pom.xml` (`maven-compiler-plugin`, `annotationProcessorPaths`) | Convenção em `AGENTS.md` |
| E8 | Ambiente local pelo `../compose.yaml` da raiz do repositório: SQL Server `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` e RabbitMQ `rabbitmq:4.3.6-management`, credenciais no `.env` | `../compose.yaml` | Convenção em `AGENTS.md` |
| D1 | Escopo restrito ao lado produtor: Pagamentos registra o pagamento e publica `PagamentoRecebido`; não há consumidor no demo | Lacunas g1, g3 e g9 | Domínio Pagamentos; convenção em `AGENTS.md` (escopo) |
| D2 | Uma única aplicação com um único banco SQL Server (driver `mssql-jdbc`), que contém `pagamento` e as tabelas da outbox | Lacuna g2 | Convenção em `AGENTS.md`; domínio Pagamentos |
| D3 | Acesso a dados com Spring Data JPA (Hibernate) e `spring-boot-starter-data-jpa`; o `OutboxWriter` grava na mesma conexão JDBC da transação JPA do `pagamento` | Lacuna g4 | Convenção em `AGENTS.md`; skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| D4 | Sem ferramenta de migração nem evolução versionada do esquema (sem Flyway ou Liquibase); a estrutura de `pagamento` é fixa. As tabelas `outbox`, `outbox_lease` e `outbox_read_point` são criadas pelo script `sql/outbox.sql`, executado manualmente no banco e idêntico em estrutura ao `schema.sql` da biblioteca; a aplicação não chama o `OutboxSchemaInstaller` | Lacuna g5; lacunas da integração do componente (esquema do banco) | Convenção em `AGENTS.md`; skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| D5 | Publicação por disparo assíncrono após o commit e recuperação das linhas pendentes na inicialização da aplicação, conforme `../mensageria-outbox/Outbox-Assync.puml`; o relay por polling da biblioteca (`RelayFactory`, estratégias `claim`/`read-point`) fica desligado (`outbox.relay.enabled=false`) | Lacuna g6 | Skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| D6 | Sem testes unitários ou de integração; o teste de scaffold `contextLoads` sobe o contexto completo e exige SQL Server e RabbitMQ disponíveis | Lacuna g7 | Convenção em `AGENTS.md` |
| D7 | A API HTTP é documentada pelo Swagger UI do springdoc (`springdoc-openapi-starter-webmvc-ui`), gerado em tempo de execução e usado para enviar requisições de teste; nenhuma outra forma de documentação adicional | Lacuna g8; revisão na implementação da spec `003-pagamentos-endpoint-inclusao-movimento` | Convenção em `AGENTS.md` |
| D8 | O `amqp-client` é fixado em 5.37.0 (propriedade `rabbit-amqp-client.version` do `pom.xml`), a versão com que a biblioteca é construída e testada, sobre a 5.30.0 gerenciada pelo Spring Boot 4.1.1 | Lacunas da integração do componente (dependências) | Convenção em `AGENTS.md`; skill técnica `integracao-mensageria-outbox-spring-boot` |
| D9 | `POST /movimentos` grava a mensagem `MovimentoRegistrado` v1 na outbox na transação da inclusão, publicada na fila quorum `movimentos.movimento-registrado` | Lacuna da confirmação de uso do componente | Domínio Pagamentos |
| D10 | A integração da outbox com o Spring Boot é compartilhada pelos artefatos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`, módulos do agregador da raiz, fora do núcleo `mensageria-outbox`; a auto-configuração nunca cria as tabelas da outbox. O demo usa o `mensageria-outbox-spring-boot4` e não tem código de integração próprio | Lacunas da extração do `OutboxGravador` | Convenção em `AGENTS.md`; skill técnica `integracao-mensageria-outbox-spring-boot`; domínio Pagamentos |
| D11 | Os artefatos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` preservam a ordem por `aggregateKey` (R11 da `mensageria-outbox`): mensagens da mesma chave publicadas uma de cada vez, em ordem de `seq`, com a consulta de linhas anteriores pendentes por `READUNCOMMITTED` antes de cada publicação | Lacuna das garantias de publicação dos artefatos | Skill técnica `integracao-mensageria-outbox-spring-boot`; READMEs dos artefatos |

## Formas de documentação a manter

A única forma adicional é o Swagger UI do springdoc, em `/swagger-ui.html`, com a especificação OpenAPI em `/v3/api-docs`, ambos gerados em tempo de execução a partir dos controllers; não há arquivo OpenAPI versionado. Não se mantêm coleções Postman, Storybook nem ADRs (D7). A biblioteca irmã mantém os próprios ADRs em `../mensageria-outbox/docs/adr/`, fora deste projeto.

## Framework spec-driven concorrente

Nenhum detectado. Não existem `.specify/`, `openspec/` nem artefatos concorrentes de specs, plans ou tasks no projeto. O diretório `.github/modernize/java-upgrade/` contém apenas scripts de hook (`recordToolUse.ps1`/`.sh`) da ferramenta de modernização de versão Java do GitHub Copilot, que não disputa o espaço dos artefatos do Clovis.

## Registro das decisões humanas (laço de lacunas)

| Lacuna | Pergunta | Decisão |
|---|---|---|
| g1 · Escopo do demo | Qual fluxo de negócio o demooutboxinbox deve demonstrar? | Só Pagamentos publicando `PagamentoRecebido` (lado outbox). O domínio Extrato sai do mapa (D1). |
| g2 · Topologia e bancos | Como organizar produtor e consumidor em aplicações e bancos no demo? | Uma aplicação com um único banco SQL Server para os dois lados. Com o consumidor fora do escopo, o banco único contém `pagamento` e as tabelas da outbox (D2). |
| g3 · Extrato | Onde fica o mecanismo de inbox (dedup, ack manual após commit, retry/backoff e DLQ)? | A biblioteca de inbox será tratada posteriormente e não é considerada agora (D1). |
| g4 · Persistência | Qual tecnologia de acesso a dados o demo deve usar? | Spring Data JPA (Hibernate), como no scaffold anterior `outbox-inbox` (D3). |
| g5 · Persistência | Como criar e evoluir o esquema das tabelas de negócio? | Sem evolução de banco de dados: nenhuma ferramenta de migração (D4). |
| g6 · Relay da outbox | Como o demo deve disparar a publicação da outbox e com qual estratégia? | Disparo assíncrono após o commit e recuperação dos pendentes na inicialização (`Outbox-Assync.puml`); o relay por polling da biblioteca não é usado (D5). |
| g7 · Testes | Qual estratégia de testes o demo deve seguir? | Sem testes unitários ou de integração no momento (D6). |
| g8 · Documentação | Quais formas de documentação o demo deve manter? | Não manter documentação adicional (D7). |
| g9 · Escopo do demo | A reconciliação das invariantes A1 a A4 entra no demo como domínio? | Não; fica fora do demo (D1). |
| Spec 003 · Documentação da API | Revogamos a D7 para adotar o Swagger UI e testar o `POST /movimentos` por ele? | Sim: a D7 é revista, o Swagger UI do springdoc é adotado e `AGENTS.md`, este documento e o `plan.md` da spec 003 são atualizados (D7). |
| Integração do componente · Biblioteca | Importar o componente é só a dependência ou também o adaptador Spring Boot da skill de integração? | Criar o adaptador: gravação, publicador, envio após o commit e recuperação dos pendentes (pacote `outbox`). |
| Integração do componente · Esquema do banco | Como fica o script DDL manual em relação ao `OutboxSchemaInstaller`? | O script manual é a única forma de criar as tabelas; a aplicação não chama o instalador (D4). |
| Integração do componente · Esquema do banco | O script cria só a `outbox` ou também `outbox_lease` e `outbox_read_point`? | As três tabelas, idênticas ao `schema.sql` da biblioteca (D4). |
| Integração do componente · Dependências | Fixar o `amqp-client` 5.37.0 da biblioteca ou manter a 5.30.0 do Spring Boot? | Fixar 5.37.0 no `pom.xml` do demo (D8). |
| Uso do componente · Domínio Pagamentos | O `POST /movimentos` publica `MovimentoRegistrado`, mas a skill dizia que a inclusão não publica evento: qual lado vale? | Manter o evento e documentar o contrato `MovimentoRegistrado` v1 na skill `pagamentos` (D9). |
| Extração do `OutboxGravador` · Arquitetura | Onde fica a integração com o Spring compartilhada entre aplicações? | Fora do núcleo `mensageria-outbox`, porque as aplicações usam versões diferentes do Spring (D10). |
| Extração do `OutboxGravador` · Escopo | O compartilhado leva só o `OutboxGravador` ou toda a integração? | Toda a integração, por auto-configuração; a aplicação só chama `gravar(envelope)` (D10). |
| Extração do `OutboxGravador` · Plataforma | Qual linha do Spring Boot atender? | As duas, com um artefato para cada uma: `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` (D10). |
| Extração do `OutboxGravador` · Repositório | Onde ficam os artefatos e com que nomes? | Módulos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` no agregador da raiz (D10). |
| Extração do `OutboxGravador` · Esquema do banco | Os artefatos criam as tabelas com o `OutboxSchemaInstaller`? | Nunca: as tabelas vêm sempre de script manual (D4, D10). |
| Extração do `OutboxGravador` · Adoção no demo | O demo passa a usar o artefato do Spring Boot 4 e remove o pacote `outbox`? | Sim (D10). |
| Garantias de publicação | A R11 exige ordem por `aggregateKey`, e os artefatos reenviavam cada mensagem de forma independente: como resolver? | Garantir a ordem nos artefatos, publicando em sequência as mensagens do mesmo `aggregateKey` (D11). |
