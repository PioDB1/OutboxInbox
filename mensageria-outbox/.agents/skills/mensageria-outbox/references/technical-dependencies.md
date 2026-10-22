# Dependências técnicas do Mensageria Outbox

> **Maintaining this file**
>
> Atualize este documento sempre que uma dependência técnica do domínio for adicionada, removida ou mudar de efeito. Refactor que preserva as dependências não pede atualização. Divergência entre este arquivo e o projeto sem decisão registrada é escalada para a pessoa responsável.

Cada item diz o que é e o que afeta no domínio se faltar.

## Dependências técnicas do domínio

- **Ambiente Docker com SQL Server e RabbitMQ (e Toxiproxy para testes)** — sem ele o relay não é executável nem testável contra os recursos do SQL Server.
- **Testcontainers e Toxiproxy** — sem eles os cenários de falha do lado produtor (relay cai depois de publicar, broker indisponível, rede instável, dois relays ativos e commits fora da ordem de `seq`) não são repetíveis.
- **ADRs** — registram a escolha entre as estratégias de relay (critério de conclusão do domínio); sem eles a decisão e as observações da PoC não ficam registradas.
- **Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey`** — sem ela a rastreabilidade ponta a ponta não existe.

## Dependências externas relevantes

- **SQL Server** — banco da outbox, do lease e do relay, executado em Docker; as duas estratégias de relay dependem dos recursos `UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion`/`min_active_rowversion()`. O H2 não oferece esses recursos e não é usado para o relay. Sem ele nenhuma das estratégias de relay funciona.
- **RabbitMQ** — broker com filas quorum e publisher confirms, acessado por adaptador. Sem ele não há publicação.
- **Micrometer** — métricas de publicação (lag da outbox, pendentes, tentativas de retry). Sem ele as métricas não são expostas.
- **Java 8 e Maven multi-módulo** — plataforma e build; Java 8 (bytecode e API) é o piso do módulo, compilado com `--release 8` em um build em JDK 21, e a suíte é verificada nos JDKs 8, 11, 17, 21 e 25. O POM `br.com.db1.poc:mensageria-outbox:0.4.0-SNAPSHOT` agrega o módulo `core`, cujo artefato `mensageria-outbox-core` é a biblioteca consumida, e é o POM pai do `core` e dos adaptadores Spring Boot; ele fixa `com.rabbitmq:amqp-client` 5.37.0 no `dependencyManagement`. O `pom.xml` da raiz só agrega os módulos e exige JDK 21 e Maven 3.9 no build a partir da raiz. O `core` também hospeda o consumidor (pacote `br.com.db1.poc.inbox`): `outbox` e `inbox` são independentes entre si e dependem só do pacote comum `br.com.db1.poc.shared`, dentro do mesmo artefato. Sem framework de aplicação no módulo; a integração com Spring Boot vive nos módulos `mensageria-outbox-spring-boot3` (Spring Boot 3.x) e `mensageria-outbox-spring-boot4` (Spring Boot 4.x), com Java 17 como piso. Sem a plataforma o módulo não compila nem executa.

## Dependências identificadas na investigação do domínio

- **Driver JDBC do SQL Server** (`mssql-jdbc` 13.4.0.jre8, escopo `runtime`; a aplicação hospedeira escolhe o do próprio classpath) — sem ele o acesso a outbox, lease e ponto de leitura não funciona.
- **Scripts de criação de esquema (outbox, lease e ponto de leitura)** — `mensageria-outbox/core/src/main/resources/br/com/db1/poc/outbox/schema/sqlserver/schema.sql`, aplicado por `OutboxSchemaInstaller.install`; as tabelas precisam existir no banco de origem antes da execução; sem eles a gravação na outbox e o lease falham. A estrutura da outbox precisa incluir os metadados de propagação e, para a estratégia de ponto de leitura, a coluna `rowversion`.
- **Configuração dos limites de heap e de memória direta do processo do relay** — sem ela o requisito de memória estável sob carga não é verificável.
- **Credenciais de banco e broker fornecidas por variáveis de ambiente ou arquivo local não versionado** — sem elas o relay não conecta; versioná-las é proibido.
- **Contexto de rastreamento que forneça o `traceId` na origem da mensagem** — sem ele o `traceId` não é persistido na outbox nem propagado nos headers e nos logs ao longo do salto assíncrono.
