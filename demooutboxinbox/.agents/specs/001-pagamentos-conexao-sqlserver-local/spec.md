# Spec: Conexão com o SQL Server local

## Visão geral

Configura a aplicação para se conectar, na inicialização, ao SQL Server local do `../compose.yaml`, no banco `master`, com o login `sa` e a porta padrão `1433`. A senha vem de `MSSQL_SA_PASSWORD`.

## Domínio

- Slug: `pagamentos`
- Skill: [`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md) — seção "Integrações e dependências externas" (SQL Server como banco único da aplicação).
- Dependências técnicas: [`.agents/skills/pagamentos/references/technical-dependencies.md`](../../skills/pagamentos/references/technical-dependencies.md), itens 3, 5 e 7.

## Escopo

**Dentro:**

- A aplicação abre conexão com o SQL Server local ao iniciar, usando o login `sa` no banco `master`.
- A porta padrão é `1433`. A porta publicada pelo ambiente local pode substituí-la.
- A senha do `sa` vem do ambiente de quem executa: da variável `MSSQL_SA_PASSWORD` exportada ou do `.env` da raiz do repositório.
- O estado da conexão com o banco aparece na verificação de saúde da aplicação.

**Fora:**

- Conexão com o RabbitMQ e a declaração explícita do relay por polling desligado (`outbox.relay.enabled=false`). Os dois fazem parte do item 7 das dependências técnicas e ficam para outra unidade.
- Criação da tabela `pagamento`, instalação das tabelas da outbox e qualquer gravação no banco.
- Criação de um banco dedicado à aplicação.
- Configuração de conexão para ambientes diferentes do local.

## Fronteira do domínio

**O que esta spec implementa:**

- Dependência `spring-boot-starter-data-jpa` no `pom.xml`. Ela entrega o `DataSource` e o `JpaTransactionManager` auto-configurados que a skill `integracao-mensageria-outbox-spring-boot` pressupõe (item 5 das dependências técnicas).
- Propriedades `spring.datasource.*` em `src/main/resources/application.properties`: URL JDBC para `localhost`, porta `${MSSQL_PORT:1433}` e banco `master`; usuário `sa`; senha `${MSSQL_SA_PASSWORD}`.
- Carga do `../.env` da raiz do repositório como fonte de configuração da aplicação. As variáveis de ambiente do processo têm precedência sobre esse arquivo.

**Pertence a outros domínios ou unidades (dependência, não vira tarefa aqui):**

- Ambiente local do `../compose.yaml` (contêiner SQL Server e `../.env`): infraestrutura do repositório, já existente e consumida como está.
- Propriedades `spring.rabbitmq.*`, `outbox.relay.*`, `OutboxSchemaInstaller`, tabela `pagamento` e entidade JPA de `pagamento`: próximas unidades do domínio Pagamentos.

## Histórias de usuário

1. Como desenvolvedor da POC, quero que a aplicação se conecte ao SQL Server local ao subir, para ter o banco único onde o domínio Pagamentos vai gravar `pagamento` e a outbox.
2. Como desenvolvedor da POC, quero que a senha do `sa` venha do `.env` ou de uma variável de ambiente, para executar a aplicação sem versionar credenciais.
3. Como desenvolvedor da POC, quero ver o estado da conexão com o banco na verificação de saúde, para confirmar que o ambiente local está pronto.

## Critérios de aceite

**História 1: conexão na inicialização**

- Dado o SQL Server do `../compose.yaml` no ar na porta `1433` e o `../.env` com `MSSQL_SA_PASSWORD`, quando a aplicação é iniciada com `./mvnw spring-boot:run` a partir de `demooutboxinbox/`, então ela sobe sem erro e se conecta ao banco `master` com o login `sa`.
- Dado o SQL Server local com o certificado TLS autoassinado gerado pelo contêiner, quando a aplicação se conecta, então a conexão é estabelecida e o certificado não causa falha.
- Dado `MSSQL_PORT` definido com outra porta no ambiente ou no `../.env`, e o SQL Server publicado nessa porta, quando a aplicação é iniciada, então ela se conecta pela porta configurada. Sem `MSSQL_PORT`, a porta é `1433`.
- Dado o ambiente local no ar, quando `./mvnw test` é executado a partir de `demooutboxinbox/`, então o teste `contextLoads` passa.

**História 2: origem da senha**

- Dado apenas o `../.env` com `MSSQL_SA_PASSWORD`, sem a variável exportada no shell, quando a aplicação é iniciada a partir de `demooutboxinbox/`, então ela usa a senha do arquivo e se conecta.
- Dado `MSSQL_SA_PASSWORD` exportada no ambiente do processo com o mesmo valor do `../.env`, quando a aplicação é iniciada, então ela usa o valor do ambiente e se conecta.
- Dado o repositório versionado, quando se inspecionam `application.properties` e os demais arquivos do projeto, então nenhum deles contém a senha do `sa` em texto literal.

**História 3: verificação de saúde**

- Dado a aplicação conectada ao SQL Server, quando se chama `GET /actuator/health/db`, então a resposta é `200` com `"status":"UP"`.
- Dado a aplicação em execução e o SQL Server parado, quando se chama `GET /actuator/health/db`, então a resposta é `503` com `"status":"DOWN"`.
- O critério consulta o componente `db`, e não o `/actuator/health` agregado. O agregado inclui o componente `rabbit`, e a conexão com o RabbitMQ está fora desta unidade.

## Comportamento atual → novo

| Aspecto | Atual | Novo |
|---|---|---|
| Acesso a dados | O `pom.xml` tem só o driver `mssql-jdbc`. Não há starter que crie um `DataSource`. | `spring-boot-starter-data-jpa` declarado, com `DataSource` e `JpaTransactionManager` auto-configurados |
| `application.properties` | Só `spring.application.name=demooutboxinbox` | Inclui `spring.datasource.*` para o SQL Server local (`localhost`, `${MSSQL_PORT:1433}`, `master`, `sa`, `${MSSQL_SA_PASSWORD}`) |
| `../.env` | Lido apenas pelo `docker compose` | Também é fonte de configuração da aplicação |
| Saúde | `/actuator/health` sem componente de banco | Componente `db` (`/actuator/health/db`) reflete a conexão com o SQL Server |

Continuam iguais: a configuração do RabbitMQ (padrão do Spring Boot), a ausência de entidades, endpoints e tabelas de negócio, e o `../compose.yaml`.

## Dependências entre domínios

Nenhuma. O domínio Pagamentos é o único da aplicação.

## Riscos e observações

- O `../.env` é resolvido em relação ao diretório de trabalho. Os comandos do `AGENTS.md` rodam a partir de `demooutboxinbox/`. Uma execução pela IDE com outro diretório de trabalho não encontra o arquivo e depende de `MSSQL_SA_PASSWORD` no ambiente.
- `pagamento` e as tabelas da outbox ficam no banco `master` do SQL Server local. Isso vale para o ambiente de demonstração e não se estende a outros ambientes.
- Com o Spring Data JPA no classpath, a aplicação precisa do SQL Server no ar para subir. Isso já vale para o `contextLoads` (item 3 das dependências técnicas).
- Os itens 5 e 7 de `technical-dependencies.md` descrevem o `pom.xml` sem o starter JPA e o `application.properties` só com `spring.application.name`. Esta unidade cumpre o item 5 e a parte de SQL Server do item 7. As partes de RabbitMQ e `outbox.relay.enabled=false` do item 7 continuam pendentes.
