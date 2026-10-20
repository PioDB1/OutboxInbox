# AGENTS.md

## Visão geral

O `demooutboxinbox` é uma POC de demonstração do lado produtor do padrão outbox. Uma aplicação Spring Boot recebe a solicitação de pagamento, registra o `pagamento` e grava a mensagem `PagamentoRecebido` na tabela `outbox` na mesma transação local, de modo que o pagamento e a mensagem existam juntos ou não existam. A publicação no RabbitMQ é disparada de forma assíncrona após o commit.

- **Domínio único:** Pagamentos, produtor de `PagamentoRecebido` e de `MovimentoRegistrado` (publicado a cada `POST /movimentos/inclusao`; `POST /movimentos/inclusao_sem_evento` grava a movimentação sem evento). A inclusão de movimentação está implementada; o registro de pagamento (`POST /pagamentos`, tabela `pagamento`, `PagamentoRecebido`) é escopo descrito na skill do domínio, a implementar por spec.
- **Fora do escopo:** consumo de mensagens e inbox (Extrato, deduplicação, ack manual, retry/DLQ do consumidor) e reconciliação das invariantes A1 a A4. Não há consumidor no demo: a mensagem publicada fica na fila do broker.
- **Mecânica de outbox:** biblioteca `br.com.db1.poc:mensageria-outbox-core:0.3.0-SNAPSHOT` (projeto irmão `../mensageria-outbox`, módulo `core`), integrada ao Spring Boot pelo artefato `br.com.db1.poc:mensageria-outbox-spring-boot4:0.3.0-SNAPSHOT` (módulo irmão `../mensageria-outbox-spring-boot4`, com o código comum em `../mensageria-outbox-spring-boot-shared`).
- **Referências de negócio:** `../mensageria-outbox/spec-caso-a-outbox-inbox.md` (Caso A: §5.1, §6.2 e RF-A1), `../mensageria-outbox/README.md` (contrato de uso da biblioteca pelo hospedeiro) e `../mensageria-outbox/Outbox-Assync.puml` (fluxo de disparo assíncrono).

## Stack

- Java 21 e Spring Boot 4.1.1 (`spring-boot-starter-parent`).
- Maven com wrapper próprio (`mvnw`/`mvnw.cmd`, Maven 3.10.0).
- `spring-boot-starter-webmvc` para a API HTTP.
- `springdoc-openapi-starter-webmvc-ui` 3.1.0 para o Swagger UI e a especificação OpenAPI gerados em tempo de execução a partir dos controllers.
- Spring Data JPA (Hibernate), pelo `spring-boot-starter-data-jpa`, para o acesso a dados.
- SQL Server pelo driver `mssql-jdbc`.
- `mensageria-outbox-spring-boot4`, auto-configuração da outbox para Spring Boot 4.x: gravação na transação (`OutboxGravador`), envio após o commit e recuperação dos pendentes na inicialização.
- RabbitMQ pelo `spring-boot-starter-amqp`; a publicação é feita pelo `RabbitMessagePublisher` da biblioteca `mensageria-outbox`, registrado pela auto-configuração. O `amqp-client` é fixado na versão 5.37.0 (propriedade `rabbit-amqp-client.version` do `pom.xml`), a mesma com que a biblioteca é construída e testada; o `spring-boot-starter-parent` 4.1.1 traria a 5.30.0.
- `spring-boot-starter-actuator` (Micrometer) para observabilidade.
- Lombok como processador de anotações na compilação principal e de testes.
- `spring-boot-devtools` em tempo de execução local.

## Estrutura do repositório

```text
demooutboxinbox/
├── pom.xml                        # build Maven, dependências e repositório JFrog-Sisprime
├── mvnw, mvnw.cmd, .mvn/wrapper/  # Maven Wrapper
├── sql/outbox.sql                 # DDL das tabelas da outbox e da tabela movimento, executado manualmente no banco
├── src/main/java/br/com/db1/demooutboxinbox/   # código da aplicação (pacote base)
├── src/main/resources/application.properties  # configuração da aplicação
├── src/test/java/br/com/db1/demooutboxinbox/   # teste de scaffold contextLoads
└── .agents/                       # contexto, mapas e skills para agentes
```

O projeto vive dentro do repositório `outbox-inbox-poc`, cuja raiz (`..`) contém o `compose.yaml` do ambiente local, o agregador Maven, a biblioteca `mensageria-outbox`, os módulos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` e o código comum `mensageria-outbox-spring-boot-shared`. Cada uma dessas pastas tem o próprio `AGENTS.md` e o próprio `.agents/`, com as skills do seu escopo; uma alteração no componente é especificada e implementada a partir da pasta que a contém.

## Convenções de arquitetura

- Uma única aplicação com um único banco SQL Server, que contém `pagamento`, `movimento` e as tabelas da outbox (`outbox`, `outbox_lease` e `outbox_read_point`).
- A integração com a `mensageria-outbox` vem da auto-configuração do `mensageria-outbox-spring-boot4`; o demo não tem código de integração próprio. Cada domínio declara a fila dos eventos que publica e grava a mensagem com `OutboxGravador.gravar(envelope)` dentro da transação do dado de negócio.
- O `OutboxWriter` grava na mesma conexão JDBC da transação JPA do `pagamento` (`autoCommit=false`); commit, rollback e fechamento da conexão são responsabilidade da aplicação.
- O script `sql/outbox.sql`, idempotente, é executado manualmente no banco antes de subir a aplicação e cria as tabelas da outbox (`outbox`, `outbox_lease` e `outbox_read_point`) e a tabela `movimento`, cada uma só quando ainda não existe. As tabelas da outbox não são entidades JPA e têm a mesma estrutura do `schema.sql` da biblioteca; a aplicação não chama o `OutboxSchemaInstaller`. A `movimento` é criada em `master.dbo`, com as colunas da `MovimentoEntity` e a restrição de verificação de `tipo` (`E` ou `S`).
- O Hibernate roda com `spring.jpa.hibernate.ddl-auto=update`: cria na inicialização a tabela de entidade JPA ainda ausente (como a `movimento`, quando o script não foi executado) e mantém a existente com os dados. O mapeamento de cada entidade declara nome, tipo e tamanho de todas as colunas iguais aos da tabela, porque o `update` altera a coluna divergente e, no SQL Server, a deixa anulável; uma mudança na `MovimentoEntity` é acompanhada da mesma mudança no `sql/outbox.sql`. Não há ferramenta de migração nem evolução versionada do esquema (sem Flyway ou Liquibase); a estrutura de `pagamento` é fixa.
- A publicação ocorre por disparo assíncrono após o commit, com recuperação das linhas pendentes (`sent_at` nulo) na inicialização. O relay por polling da biblioteca (`RelayFactory`, estratégias `claim`/`read-point`) não é usado: a auto-configuração nunca o instancia, e o `outbox.relay.enabled=false` do `application.properties` apenas registra essa decisão.
- A publicação é at-least-once: uma mensagem pode ser publicada mais de uma vez.
- Os logs de publicação incluem as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`.
- A configuração da aplicação fica em `src/main/resources/application.properties`.
- Código Java e `pom.xml` são indentados com tabulação.

## Restrições globais

- Os artefatos `mensageria-outbox-spring-boot4` e `mensageria-outbox-core` 0.3.0-SNAPSHOT são resolvidos do repositório `JFrog-Sisprime` (`https://artifactory.sisprimedobrasil.net:443/artifactory/dependencies-maven/`), declarado no `pom.xml`, ou do repositório Maven local depois de `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install` na raiz do repositório.
- O projeto é independente do agregador Maven da raiz (`../pom.xml`), que lista os módulos `mensageria-outbox`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`; o build do demo roda a partir desta pasta, com o wrapper próprio.
- Credenciais de banco e broker chegam por variáveis de ambiente ou pelo arquivo `.env` da raiz do repositório e nunca são versionadas (o `.env` está no `../.gitignore`). As variáveis exigidas são as referenciadas em `../compose.yaml`.
- Não há testes unitários nem de integração além do teste de scaffold `contextLoads`, que sobe o contexto completo e exige SQL Server e RabbitMQ disponíveis.
- A documentação da API HTTP é o Swagger UI do springdoc, gerado em tempo de execução e usado para enviar requisições de teste aos endpoints; não há especificação OpenAPI versionada no repositório. Não se mantém outra documentação adicional (sem coleções Postman/Insomnia nem ADRs neste projeto). Os ADRs da biblioteca ficam em `../mensageria-outbox/docs/adr/`.

## Comandos essenciais

Execute a partir desta pasta (`demooutboxinbox/`), exceto quando indicado. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.

| Objetivo | Comando |
|---|---|
| Subir SQL Server e RabbitMQ locais (na raiz do repositório, `..`) | `docker compose up -d` |
| Instalar no repositório Maven local o `mensageria-outbox` e os artefatos Spring Boot (na raiz do repositório, `..`, com Maven e JDK 21) | `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install` |
| Criar as tabelas da outbox e a `movimento` no banco local (idempotente; a senha do `sa` vem do `.env`) | `sqlcmd -S localhost,1433 -U sa -d master -i sql/outbox.sql` |
| Compilar | `./mvnw compile` |
| Executar a aplicação | `./mvnw spring-boot:run` |
| Abrir o Swagger UI (com a aplicação no ar) | `http://localhost:8081/swagger-ui.html` (especificação OpenAPI em `http://localhost:8081/v3/api-docs`) |
| Rodar o teste `contextLoads` (com o ambiente local no ar) | `./mvnw test` |
| Empacotar | `./mvnw package` |

Não há ferramenta de lint ou formatação configurada.

## Estrutura `.agents/`

- `.agents/context/` — memória da descoberta funcional: objetivo, escopo, restrições e decisões humanas (`discovery-answers.md`).
- `.agents/maps/` — mapa funcional dos domínios de negócio, com evidências, regras inferidas e dependências (`functional-map.md`).
- `.agents/skills/` — skills de negócio (por domínio) e skills técnicas (transversais), carregadas sob demanda pelo agente a partir da `description` do frontmatter de cada uma.

## Precedência das skills

- Antes de criar ou editar arquivos, ou de implementar qualquer funcionalidade, verifique se alguma skill em `.agents/skills/` governa o caso — pela tecnologia, padrão de código ou arquitetura envolvidos, ou pelo domínio de negócio, funcionalidade ou módulo em questão — e siga-a antes de agir.
- As skills em `.agents/skills/` são autoritativas e prevalecem sobre padrões inferidos do código existente.
- O corpo de cada skill indica onde vive a documentação completa do seu assunto e qual fonte detém a verdade, dentro ou fora do repositório; siga essa indicação a partir da skill.

## Escrita de comentários e documentação

Todo texto que vive neste repositório — comentário de código, docstring, skill, `AGENTS.md`, README, spec — descreve o estado atual, escrito para quem abre o arquivo hoje sem conhecer o histórico do arquivo nem a conversa que o produziu.

- **Descreva o que é, nunca a transição.** Ao editar um texto existente, reescreva o trecho a partir do resultado final, como se sempre tivesse sido assim. Uma frase que só faz sentido para quem viu a versão anterior — ou o seu próprio diff — não pertence ao arquivo: o que mudou pertence à mensagem de commit e à descrição do pull request.
- **Negue apenas para evitar um erro plausível.** Uma negação merece seu lugar quando um leitor competente de fato tentaria a alternativa e a frase diz por que ela falha, protegendo uma mudança futura. Contraste com a versão anterior, com uma alternativa que ninguém tentaria ou com o que o código já mostra é ruído que custa a atenção do leitor.
- **Faça a poda antes de concluir a tarefa.** Releia os textos que você criou ou alterou e corte o que falhar nas duas regras acima. Expressões como "não mais", "costumava", "agora é", "em vez de", "ao invés de", "diferentemente de" e "sem precisar" são os sintomas usuais — mantenha apenas as que sobrevivem à segunda regra.

Uma exceção: quando o assunto do texto **é** uma mudança — uma mensagem de commit, uma descrição de pull request, a spec de uma unidade de manutenção, um changelog — a transição é o conteúdo. A regra proíbe narrar a *edição do texto*, nunca a mudança de que o texto trata.
