# Casos de teste: Entidade de movimento

## Pré-condições

Valem para todos os casos, exceto quando o caso indicar outra coisa:

- O SQL Server e o RabbitMQ do `../compose.yaml` estão no ar. Para subi-los, execute `docker compose up -d` na raiz do repositório (`..`).
- O `../.env` da raiz define `MSSQL_SA_PASSWORD` com a senha do login `sa` do contêiner.
- Os comandos Maven rodam a partir de `demooutboxinbox/`. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.
- As consultas ao banco são feitas no banco `master` do SQL Server local com o login `sa`, numa sessão interativa do `sqlcmd` do contêiner, aberta a partir da raiz do repositório:

  ```sh
  docker compose exec sqlserver bash -c '/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -d master'
  ```

  Na sessão, cada comando é digitado e seguido de uma linha com `GO`. A sessão interativa evita conflito entre as aspas simples dos comandos SQL e as do shell. A senha vem da variável do próprio contêiner e não aparece neste arquivo. Os casos se referem a essa sessão como "execute no banco".
- "Tabela ausente" significa que, antes do caso, foi executado no banco `DROP TABLE IF EXISTS movimento`.

## História 1: entidade de movimento

### TC-1 (obrigatório): a `MovimentoEntity` é uma entidade JPA mapeada para `movimento`

1. Abra `src/main/java/br/com/db1/demooutboxinbox/movimento/MovimentoEntity.java`.
2. Verifique as anotações da classe.

**Esperado:** a classe fica sob o pacote base `br.com.db1.demooutboxinbox`, no subpacote `movimento`, e tem `@Entity` e `@Table(name = "movimento")`.

### TC-2 (obrigatório): os campos da entidade são mapeados para as colunas da tabela

1. No mesmo arquivo do TC-1, verifique os campos e as anotações de cada um.

**Esperado:** a entidade tem os campos `Long id`, `Instant dataHora`, `char tipo`, `String descricao` e `BigDecimal valor`, mapeados para as colunas `id`, `data_hora`, `tipo`, `descricao` e `valor` conforme o `data-model.md`: `id` com `@Id` e `@GeneratedValue(strategy = GenerationType.IDENTITY)`; `data_hora` com `columnDefinition = "datetime2"`; `tipo` com comprimento 1 e a restrição de verificação `tipo in ('E','S')` declarada no `@Column`; `descricao` com comprimento 255; `valor` com `@JdbcTypeCode(SqlTypes.DECIMAL)`, precisão 18 e escala 2. Todas as colunas exceto `id` têm `nullable = false`.

### TC-3 (obrigatório): o tipo é um campo `char`, sem classe de enumeração

1. No mesmo arquivo do TC-1, verifique a declaração do campo `tipo`.
2. Liste os arquivos de `src/main/java/br/com/db1/demooutboxinbox/movimento/`.

**Esperado:** o campo `tipo` é do tipo `char`, sem `@Enumerated`, e a restrição do `@Column` aceita só `E` e `S`. O subpacote `movimento` contém só a `MovimentoEntity`, sem classe de enumeração para o tipo.

## História 2: criação da tabela na subida

### TC-4 (obrigatório): a subida cria a tabela ausente

1. Com a tabela ausente, execute no banco `SELECT OBJECT_ID('movimento')` e confirme que o resultado é `NULL`.
2. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/` e acompanhe o log até a aplicação concluir a inicialização.
3. Execute no banco `SELECT OBJECT_ID('movimento')`.

**Esperado:** a aplicação conclui a inicialização sem erro. No passo 3, o resultado não é `NULL`: a tabela `movimento` existe.

### TC-5 (obrigatório): as colunas da tabela criada têm tipo, tamanho e obrigatoriedade definidos

1. Com a tabela criada pela aplicação no TC-4, execute no banco:

   ```sql
   SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE
   FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'movimento'
   ```

**Esperado:** a consulta devolve exatamente cinco colunas, todas com `IS_NULLABLE = NO`: `id` (`bigint`), `data_hora` (`datetime2`), `tipo` (`char`, 1), `descricao` (`varchar`, 255) e `valor` (`decimal`, precisão 18, escala 2).

### TC-6 (obrigatório): `id` é identidade e chave primária

1. Com a tabela criada pela aplicação no TC-4, execute no banco `SELECT COLUMNPROPERTY(OBJECT_ID('movimento'), 'id', 'IsIdentity')`.
2. Execute no banco:

   ```sql
   SELECT kcu.COLUMN_NAME
   FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
   JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
   WHERE tc.TABLE_NAME = 'movimento' AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
   ```

**Esperado:** o passo 1 devolve `1`. O passo 2 devolve uma única linha, com `id`.

### TC-7 (recomendado): a tabela tem a restrição de valores de `tipo`

1. Com a tabela criada pela aplicação no TC-4, execute no banco `SELECT definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID('movimento')`.

**Esperado:** existe uma restrição de verificação sobre `tipo` que aceita só `'E'` e `'S'`.

### TC-8 (obrigatório): uma linha válida é gravada com `id` gerado pelo banco

1. Com a tabela criada pela aplicação no TC-4, execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', 'Teste', 10.50)`.
2. Execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'S', 'Teste saída', 3.25)`.
3. Execute no banco `SELECT id, data_hora, tipo, descricao, valor FROM movimento ORDER BY id`.

**Esperado:** os dois `INSERT` são aceitos. A consulta devolve as duas linhas, cada uma com um `id` numérico gerado pelo banco e distinto, `tipo` igual a `E` e `S`, e `valor` igual a `10.50` e `3.25`.

### TC-9 (obrigatório): `descricao` nula é recusada

1. Com a tabela criada pela aplicação no TC-4, execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', NULL, 10.50)`.
2. Execute no banco `SELECT COUNT(*) FROM movimento WHERE descricao IS NULL`.

**Esperado:** o banco recusa o `INSERT` com erro. A contagem do passo 2 é `0`.

### TC-10 (obrigatório): `descricao` com mais de 255 caracteres é recusada

1. Com a tabela criada pela aplicação no TC-4, execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', REPLICATE('a', 256), 10.50)`.
2. Execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', REPLICATE('b', 255), 10.50)`.
3. Execute no banco `SELECT LEN(descricao) FROM movimento WHERE descricao LIKE 'a%' OR descricao LIKE 'b%'`.

**Esperado:** o banco recusa o `INSERT` do passo 1 com erro e aceita o do passo 2. A consulta do passo 3 devolve uma única linha, com `255`.

### TC-11 (obrigatório): `tipo` diferente de `E` e `S` é recusado

1. Com a tabela criada pela aplicação no TC-4, execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'X', 'Teste', 10.50)`.
2. Execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'ENTRADA', 'Teste', 10.50)`.
3. Execute no banco `SELECT COUNT(*) FROM movimento WHERE tipo NOT IN ('E','S')`.

**Esperado:** o banco recusa o `INSERT` do passo 1 com erro de violação da restrição de verificação de `tipo` e o do passo 2 com erro de texto maior que a coluna. A contagem do passo 3 é `0`.

### TC-12 (recomendado): `data_hora`, `tipo` e `valor` nulos são recusados

1. Com a tabela criada pela aplicação no TC-4, execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (NULL, 'E', 'Teste', 10.50)`.
2. Execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), NULL, 'Teste', 10.50)`.
3. Execute no banco `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', 'Teste', NULL)`.

**Esperado:** o banco recusa os três `INSERT` com erro.

### TC-13 (obrigatório): o teste `contextLoads` passa com a tabela ausente

1. Com a aplicação parada e a tabela ausente, execute `./mvnw test` a partir de `demooutboxinbox/`.
2. Execute no banco `SELECT OBJECT_ID('movimento')`.

**Esperado:** o build termina com sucesso, e o teste `contextLoads` de `DemooutboxinboxApplicationTests` passa. No passo 2, o resultado não é `NULL`: a execução do teste criou a tabela `movimento`.

## História 3: tabela existente preservada

### TC-14 (obrigatório): a reinicialização mantém a tabela e as linhas gravadas

1. Com a tabela criada pela aplicação e as linhas do TC-8 gravadas, execute no banco `SELECT id, data_hora, tipo, descricao, valor FROM movimento ORDER BY id` e anote o resultado.
2. Execute no banco `SELECT create_date, modify_date FROM sys.tables WHERE name = 'movimento'` e anote o resultado.
3. Pare a aplicação.
4. Execute `./mvnw spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.hibernate.SQL=debug` a partir de `demooutboxinbox/` e acompanhe o log até a aplicação concluir a inicialização.
5. Repita as consultas dos passos 1 e 2.
6. Repita a consulta do TC-5.

**Esperado:** a aplicação conclui a inicialização sem erro, e o log da subida não tem `create table` nem `alter table` para `movimento`. As linhas do passo 5 são as mesmas do passo 1, com os mesmos valores. `create_date` e `modify_date` não mudam. As colunas continuam como no TC-5, todas com `IS_NULLABLE = NO`.

### TC-15 (obrigatório): tabelas do banco que não são entidades não são alteradas

1. Execute no banco `CREATE TABLE tc_tabela_externa (codigo INT NOT NULL); INSERT INTO tc_tabela_externa VALUES (1);`.
2. Execute no banco `SELECT create_date, modify_date FROM sys.tables WHERE name = 'tc_tabela_externa'` e anote o resultado.
3. Execute `./mvnw spring-boot:run` a partir de `demooutboxinbox/` e acompanhe o log até a aplicação concluir a inicialização.
4. Execute no banco `SELECT codigo FROM tc_tabela_externa` e repita a consulta do passo 2.
5. Pare a aplicação e execute no banco `DROP TABLE tc_tabela_externa`.

**Esperado:** a aplicação conclui a inicialização sem erro. No passo 4, a tabela `tc_tabela_externa` existe, contém a linha com `codigo = 1`, e `create_date` e `modify_date` são os mesmos do passo 2.
