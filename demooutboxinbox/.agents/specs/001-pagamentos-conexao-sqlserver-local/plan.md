# Plano: Conexão com o SQL Server local

## Stack e estrutura

A stack e a estrutura são as do `AGENTS.md`. A unidade altera dois arquivos e não cria classe Java:

- `pom.xml`: recebe o starter de acesso a dados.
- `src/main/resources/application.properties`: recebe a importação do `../.env`, as propriedades `spring.datasource.*` e a exposição dos componentes de saúde.

O `DataSource` e o `JpaTransactionManager` são os auto-configurados pelo Spring Boot, como pressupõe a skill [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md).

## Decisões técnicas

Uma linha por veredito. Contexto, alternativas e evidências estão em [`research.md`](./research.md).

- **Starter de acesso a dados:** `spring-boot-starter-data-jpa` no `pom.xml`, sem versão explícita (gerenciada pelo `spring-boot-starter-parent` 4.1.1). Cumpre o item 5 de `technical-dependencies.md`.
- **URL JDBC:** `jdbc:sqlserver://localhost:${MSSQL_PORT:1433};databaseName=master;trustServerCertificate=true`. Mantém a criptografia padrão do `mssql-jdbc` 13.4.0 e aceita o certificado autoassinado do contêiner.
- **Credenciais:** `spring.datasource.username=sa` e `spring.datasource.password=${MSSQL_SA_PASSWORD}`. Nenhuma senha literal entra em arquivo versionado.
- **Origem das variáveis:** `spring.config.import=optional:file:../.env[.properties]`. O `../.env` é lido como `.properties`, e as variáveis de ambiente do processo têm precedência sobre ele.
- **Saúde:** `management.endpoint.health.show-components=always`, para que `GET /actuator/health/db` responda com o status do componente `db` (`200`/`UP` ou `503`/`DOWN`) sem expor detalhes.
- **Esquema:** o padrão `ddl-auto=none` do Spring Boot para banco não embarcado continua valendo. A unidade não cria tabelas nem entidades.

O `application.properties` resultante tem este conteúdo:

```properties
spring.application.name=demooutboxinbox

spring.config.import=optional:file:../.env[.properties]

spring.datasource.url=jdbc:sqlserver://localhost:${MSSQL_PORT:1433};databaseName=master;trustServerCertificate=true
spring.datasource.username=sa
spring.datasource.password=${MSSQL_SA_PASSWORD}

management.endpoint.health.show-components=always
```

## Modelo de dados

Sem `data-model.md`. A unidade não cria tabela, entidade nem coluna, só a conexão com o banco `master`.

## Contratos externos

Sem `contracts/`. A unidade não cria nem altera contrato próprio. O endpoint `/actuator/health/db` é o do Spring Boot Actuator, só exposto por configuração.

## Interface

Sem `ui/`. A unidade é só de backend e configuração.

## Estratégia de testes

A decisão D6 de `discovery-answers.md` e o `AGENTS.md` definem que não há testes unitários nem de integração além do `contextLoads`. A unidade não cria teste nem infraestrutura de teste. A verificação dos critérios de aceite usa o que já existe:

- **`contextLoads`:** `./mvnw test` a partir de `demooutboxinbox/`, com o `../compose.yaml` no ar. O contexto passa a criar o `DataSource` e o Hibernate, que conectam ao `master` com o login `sa`. O teste falha se a conexão falhar.
- **Execução manual:** `./mvnw spring-boot:run` a partir de `demooutboxinbox/`, seguido de `GET /actuator/health/db`. Com o SQL Server no ar, a resposta é `200` com `"status":"UP"`. Com o SQL Server parado, é `503` com `"status":"DOWN"`.
- **Origem da senha:** repetir a execução com `MSSQL_SA_PASSWORD` só no `../.env` e depois também exportada no shell. As duas execuções conectam.
- **Senha fora do versionamento:** `git grep` pelo valor da senha no repositório não encontra ocorrência.

## Impacto na documentação autoritativa

Há desvio deliberado. A spec desta unidade define que o `../.env` da raiz passa a ser fonte de configuração da aplicação, e o `AGENTS.md` já admite o `.env` como origem das credenciais. A implementação também cumpre precondições que a documentação descreve como ausentes. Os documentos afetados são:

- **[`.agents/skills/integracao-mensageria-outbox-spring-boot/SKILL.md`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md)**, seção "1. Dependências e configuração": hoje diz que as credenciais chegam por variáveis de ambiente referenciadas por placeholders. Ela passa a dizer também que o `application.properties` importa o `../.env` com `spring.config.import=optional:file:../.env[.properties]` e que as variáveis do processo têm precedência sobre o arquivo. A nota de manutenção da skill pede atualização quando muda a fonte de configuração.
- **[`.agents/skills/pagamentos/references/technical-dependencies.md`](../../skills/pagamentos/references/technical-dependencies.md)**, itens 5 e 7: hoje dizem que o `pom.xml` ainda não declara o `spring-boot-starter-data-jpa` e que o `application.properties` contém apenas `spring.application.name`. Depois desta unidade, o item 5 está atendido e o item 7 fica atendido na parte do SQL Server. Continuam pendentes a configuração do RabbitMQ e o `outbox.relay.enabled=false` declarado.

A skill de domínio [`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md) não muda: as regras de negócio e a indicação do SQL Server como banco único continuam válidas.
