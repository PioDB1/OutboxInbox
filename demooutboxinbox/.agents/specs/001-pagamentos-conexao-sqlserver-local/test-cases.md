# Casos de teste: Conexão com o SQL Server local

## Pré-condições

Valem para todos os casos, exceto quando o caso indicar outra coisa:

- O SQL Server e o RabbitMQ do `../compose.yaml` estão no ar. Para subi-los, execute `docker compose up -d` na raiz do repositório (`..`).
- O `../.env` da raiz define `MSSQL_SA_PASSWORD` com a senha do login `sa` do contêiner e `MSSQL_PORT=1433`.
- `MSSQL_SA_PASSWORD` e `MSSQL_PORT` não estão exportadas no shell de quem executa o teste.
- Os comandos Maven rodam a partir de `demooutboxinbox/`. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.
- A porta HTTP padrão do Spring Boot (`8080`) está livre. Os endereços abaixo usam `http://localhost:8080`.
- As consultas ao banco são feitas no SQL Server local com o login `sa`.
- A senha do `sa` não aparece neste arquivo. Os casos se referem a ela como "o valor de `MSSQL_SA_PASSWORD` do `../.env`".

## História 1: conexão na inicialização

### TC-1 (obrigatório): a aplicação sobe e se conecta ao `master` com o login `sa`

1. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
2. Acompanhe o log até a aplicação concluir a inicialização.
3. No SQL Server local, consulte as sessões ativas em `sys.dm_exec_sessions` e o banco de cada uma (`DB_NAME(database_id)`).

**Esperado:** a aplicação conclui a inicialização sem erro. Existe ao menos uma sessão aberta pela aplicação com `login_name = sa` no banco `master`.

### TC-2 (obrigatório): o certificado autoassinado não impede a conexão, e a conexão é criptografada

1. Com a aplicação iniciada como no TC-1, procure no log da inicialização erros de certificado ou de TLS (`PKIX`, `SSL`).
2. No SQL Server local, consulte `encrypt_option` em `sys.dm_exec_connections` para as sessões da aplicação identificadas no TC-1.

**Esperado:** o log não tem erro de certificado nem de TLS. As conexões da aplicação têm `encrypt_option = TRUE`.

### TC-3 (recomendado): a porta definida em `MSSQL_PORT` é usada

1. No `../.env`, troque `MSSQL_PORT` por `14330`.
2. Na raiz do repositório, execute `docker compose up -d` para republicar o SQL Server na porta `14330`.
3. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
4. Chame `GET http://localhost:8080/actuator/health/db`.
5. Pare a aplicação, volte `MSSQL_PORT=1433` no `../.env` e execute `docker compose up -d` de novo.

**Esperado:** no passo 4, a resposta é `200` com `"status":"UP"`. A aplicação se conectou pela porta `14330`.

### TC-4 (recomendado): sem `MSSQL_PORT`, a porta é `1433`

1. Remova a linha `MSSQL_PORT` do `../.env`. O SQL Server continua publicado na porta `1433`, que é o padrão do `../compose.yaml`.
2. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
3. Chame `GET http://localhost:8080/actuator/health/db`.
4. Pare a aplicação e devolva `MSSQL_PORT=1433` ao `../.env`.

**Esperado:** no passo 3, a resposta é `200` com `"status":"UP"`. A aplicação se conectou pela porta `1433`.

### TC-5 (obrigatório): o teste `contextLoads` passa com o ambiente local no ar

1. Com a aplicação parada, execute `./mvnw test` a partir de `demooutboxinbox/`.

**Esperado:** o build termina com sucesso, e o teste `contextLoads` de `DemooutboxinboxApplicationTests` passa. O contexto sobe com o `DataSource` conectado ao SQL Server local.

## História 2: origem da senha

### TC-6 (obrigatório): a senha vem só do `../.env`

1. Confirme que `MSSQL_SA_PASSWORD` não está definida no ambiente do shell.
2. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
3. Chame `GET http://localhost:8080/actuator/health/db`.

**Esperado:** a aplicação sobe, e a resposta é `200` com `"status":"UP"`. A senha foi lida do `../.env`.

### TC-7 (obrigatório): a senha exportada no ambiente é usada

1. Exporte no shell `MSSQL_SA_PASSWORD` com o mesmo valor de `MSSQL_SA_PASSWORD` do `../.env`.
2. No mesmo shell, execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
3. Chame `GET http://localhost:8080/actuator/health/db`.

**Esperado:** a aplicação sobe, e a resposta é `200` com `"status":"UP"`.

### TC-8 (recomendado): a variável do ambiente tem precedência sobre o `../.env`

1. Exporte no shell `MSSQL_SA_PASSWORD` com um valor diferente da senha do `sa`. O `../.env` continua com a senha correta.
2. No mesmo shell, execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
3. Acompanhe o log da inicialização.
4. Remova a variável exportada do shell.

**Esperado:** a aplicação não se conecta ao SQL Server, e o log registra a falha de autenticação do login `sa`. Isso mostra que o valor do ambiente prevaleceu sobre o do `../.env`.

### TC-9 (obrigatório): nenhum arquivo versionado contém a senha do `sa`

1. Na raiz do repositório, busque o valor de `MSSQL_SA_PASSWORD` do `../.env` nos arquivos versionados (`git grep -F` com esse valor).
2. Abra `src/main/resources/application.properties`.

**Esperado:** a busca não encontra ocorrência. Em `application.properties`, `spring.datasource.password` é `${MSSQL_SA_PASSWORD}` e `spring.datasource.username` é `sa`.

## História 3: verificação de saúde

### TC-10 (obrigatório): componente `db` com o SQL Server no ar

1. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/`.
2. Chame `GET http://localhost:8080/actuator/health/db`.

**Esperado:** a resposta é `200` com `"status":"UP"`.

### TC-11 (obrigatório): componente `db` com o SQL Server parado

1. Com a aplicação iniciada como no TC-10, execute `docker compose stop sqlserver` na raiz do repositório.
2. Chame `GET http://localhost:8080/actuator/health/db`.
3. Execute `docker compose start sqlserver` na raiz do repositório e espere o contêiner ficar saudável.
4. Chame `GET http://localhost:8080/actuator/health/db` de novo.

**Esperado:** no passo 2, a resposta é `503` com `"status":"DOWN"`. No passo 4, a resposta volta a ser `200` com `"status":"UP"`.

### TC-12 (recomendado): a saúde agregada lista os componentes sem detalhes

1. Com a aplicação iniciada como no TC-10, chame `GET http://localhost:8080/actuator/health`.

**Esperado:** o corpo traz `components` com o componente `db` em `"status":"UP"` e o componente `rabbit`. Nenhum componente traz `details`. O status agregado reflete o `rabbit` em `DOWN`, porque a conexão com o RabbitMQ está fora desta unidade.
