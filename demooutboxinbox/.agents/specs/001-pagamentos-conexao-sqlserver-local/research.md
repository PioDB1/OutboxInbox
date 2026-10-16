# Pesquisa: Conexão com o SQL Server local

## Starter de acesso a dados

**Contexto.** O `pom.xml` declara o driver `mssql-jdbc`, mas nenhum starter que auto-configure um `DataSource`. Só com `spring.datasource.*`, a aplicação não abre conexão.

**Alternativas:**

- **`spring-boot-starter-data-jpa`**: traz `spring-boot-jdbc` (`DataSource` com HikariCP), Hibernate e `JpaTransactionManager`.
- **`spring-boot-starter-jdbc`**: traz só o `DataSource` e o `DataSourceTransactionManager`.

**Decisão:** `spring-boot-starter-data-jpa`, com a versão gerenciada pelo `spring-boot-starter-parent` 4.1.1.

**Evidência:** a decisão D3 de `.agents/context/discovery-answers.md` define, sem reserva, o Spring Data JPA como tecnologia de acesso a dados. O item 5 de `.agents/skills/pagamentos/references/technical-dependencies.md` e a seção 1 da skill `integracao-mensageria-outbox-spring-boot` exigem esse starter e o `JpaTransactionManager` auto-configurado. Os artefatos `spring-boot-starter-data-jpa`, `spring-boot-data-jpa`, `spring-boot-jdbc` e `spring-boot-hibernate` 4.1.1 já estão no repositório Maven local.

**Consequências:** na inicialização, o Hibernate abre uma conexão para ler os metadados JDBC e detectar o dialeto. A aplicação passa a depender do SQL Server no ar para subir. O `ddl-auto` padrão para banco não embarcado é `none`: o Hibernate não cria tabelas, o que está de acordo com a decisão D4.

## Criptografia da conexão (TLS)

**Contexto.** O driver gerenciado pelo Spring Boot 4.1.1 é o `mssql-jdbc` `13.4.0.jre11` (`spring-boot-dependencies-4.1.1.pom`, propriedade `mssql-jdbc.version`). Ele usa `encrypt=true` por padrão. O SQL Server do `../compose.yaml` apresenta um certificado autoassinado gerado pelo contêiner.

**Alternativas:**

- **`trustServerCertificate=true`**: mantém a criptografia e aceita o certificado do servidor sem validar a cadeia.
- **`encrypt=false`**: desliga a criptografia da conexão.

**Decisão:** `trustServerCertificate=true` na URL JDBC.

**Evidência:** a conexão foi testada no `jshell` com o jar `mssql-jdbc-13.4.0.jre11.jar` do repositório Maven local contra o SQL Server local, com login `sa` e a senha do `../.env`:

- `jdbc:sqlserver://localhost:1433;databaseName=master` falha com `SQLServerException`: com `encrypt=true` e `trustServerCertificate=false`, o driver não estabelece a conexão segura (`PKIX path building failed`).
- `jdbc:sqlserver://localhost:1433;databaseName=master;trustServerCertificate=true` conecta, e a consulta devolve `DB_NAME()=master`, `SUSER_SNAME()=sa` e `encrypt_option=TRUE` em `sys.dm_exec_connections`.

**Consequências:** o tráfego com o banco local continua criptografado. A confiança incondicional no certificado vale só para o SQL Server local de demonstração, único alvo desta configuração.

## Origem da senha: importação do `../.env`

**Contexto.** A senha do `sa` precisa vir de `MSSQL_SA_PASSWORD`, exportada no ambiente ou definida no `../.env` da raiz. O Spring Boot não lê arquivos `.env` sozinho. O `../.env` tem o formato `CHAVE=valor`, com comentários iniciados por `#`.

**Alternativas:**

- **`spring.config.import=optional:file:../.env[.properties]`**: carrega o arquivo como fonte de configuração, interpretado como `.properties`.
- **Exigir a variável exportada no shell**: cada execução precisaria de um passo manual fora do Maven.

**Decisão:** `spring.config.import=optional:file:../.env[.properties]` em `application.properties`, com `spring.datasource.password=${MSSQL_SA_PASSWORD}` e a porta `${MSSQL_PORT:1433}` na URL.

**Evidência:**

- `StandardConfigDataLocationResolver` (`spring-boot-4.1.1-sources.jar`, linhas 219–228) aplica a extensão da dica de arquivo (`[.properties]`) para escolher o `PropertySourceLoader` de um arquivo sem extensão reconhecida.
- `ConfigDataEnvironment` (linha 368) adiciona as fontes importadas com `propertySources.addLast`, depois das variáveis de ambiente do processo. Por isso uma `MSSQL_SA_PASSWORD` exportada tem precedência sobre o valor do `../.env`.
- O formato `.properties` trata `#` como comentário só no início da linha. O `#` dentro do valor de `MSSQL_SA_PASSWORD` é preservado.
- O prefixo `optional:` permite subir sem o arquivo, quando a variável vem do ambiente. O caminho `file:../.env` é relativo ao diretório de trabalho. O `spring-boot:run` e o Surefire usam `demooutboxinbox/` como diretório de trabalho, que é onde o `AGENTS.md` manda executar os comandos.
- O `AGENTS.md` (restrições globais) define que as credenciais chegam por variáveis de ambiente ou pelo `.env` da raiz e nunca são versionadas.

**Consequências:** as demais chaves do `../.env` (`RABBITMQ_*`, `TOXIPROXY_*`) também entram no `Environment` da aplicação. Nenhuma propriedade do Spring usa esses nomes, então elas não mudam o comportamento da aplicação. Uma execução com outro diretório de trabalho não encontra o arquivo e depende da variável exportada.

## Componente `db` na verificação de saúde

**Contexto.** A spec verifica a conexão por `GET /actuator/health/db`. O `DataSourceHealthContributorAutoConfiguration` (`spring-boot-jdbc` 4.1.1) registra o indicador `db` sempre que existe um bean `DataSource`.

**Alternativas:**

- **`management.endpoint.health.show-components=always`**: expõe os caminhos dos componentes, como `/actuator/health/db`, e omite os detalhes.
- **`management.endpoint.health.show-details=always`**: também expõe os componentes, mas inclui os detalhes de cada um, como o produto do banco e a consulta de validação.

**Decisão:** `management.endpoint.health.show-components=always`.

**Evidência:**

- `HealthEndpointSupport.getResult` (`spring-boot-health-4.1.1-sources.jar`, linhas 97–102) devolve `null` (`404`) para qualquer caminho abaixo da raiz quando `showComponents` é falso.
- `AutoConfiguredHealthEndpointGroup.showComponents` usa `showDetails` quando `show-components` não é configurado.
- O padrão de `show-details` em `HealthEndpointProperties` (linha 43) é `Show.NEVER`.
- Sem configuração, portanto, `/actuator/health/db` responde `404`.

**Consequências:** `/actuator/health` passa a listar os componentes, entre eles `db` e `rabbit`, só com o status de cada um. O componente `rabbit` fica `DOWN` enquanto a conexão com o RabbitMQ não for configurada. Por isso a verificação desta unidade usa `/actuator/health/db`, e não o agregado. A resposta `503` com `DOWN` vem do mapeamento HTTP padrão de status do actuator.
