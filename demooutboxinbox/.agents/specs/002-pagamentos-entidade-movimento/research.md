# Pesquisa: Entidade de movimento

As evidências vêm do código-fonte das versões gerenciadas pelo `spring-boot-starter-parent` 4.1.1 (`hibernate.version` = `7.4.5.Final` no `spring-boot-dependencies-4.1.1.pom`), lido dos jars `hibernate-core-7.4.5.Final-sources.jar` e `spring-boot-hibernate-4.1.1-sources.jar` do repositório Maven local.

## Criação da tabela na subida

**Contexto.** A spec pede que a tabela `movimento` seja criada pela entidade na inicialização, só quando ainda não existe, mantendo uma tabela existente com os seus dados. O padrão do Spring Boot para banco não embarcado é `ddl-auto=none`.

**Alternativas:**

- **`spring.jpa.hibernate.ddl-auto=update`**: o Hibernate compara as entidades com o banco, cria as tabelas ausentes e ajusta as existentes.
- **`spring.jpa.hibernate.ddl-auto=create`** ou **`create-drop`**: removem e recriam as tabelas das entidades a cada subida e apagam os dados.
- **Script `schema.sql` com `spring.sql.init`**: cria a tabela por SQL escrito à mão, fora da entidade.

**Decisão:** `spring.jpa.hibernate.ddl-auto=update` em `application.properties`.

**Evidência:**

- O pedido da unidade define que a entidade cria a tabela e que uma tabela existente não é recriada. Isso exclui `create`/`create-drop` (recriam) e `schema.sql` (a tabela não viria da entidade).
- `HibernateProperties` (`@ConfigurationProperties("spring.jpa.hibernate")`) repassa `ddl-auto` para `hibernate.hbm2ddl.auto` quando o valor é diferente de `none`. `HibernateDefaultDdlAutoProvider` devolve `none` para banco não embarcado, que é o valor vigente.
- `AbstractSchemaMigrator` cria a tabela mapeada que não existe no banco (`createTable`) e, para a existente, chama `migrateTable`. `StandardTableMigrator.sqlAlterStrings` gera só `add` para coluna ausente e `alter column` para coluna com tipo ou tamanho divergente. Não há `drop table` nesse caminho, e as tabelas que não são entidades (como `outbox`, `outbox_lease` e `outbox_read_point`) não entram na comparação.

**Consequências:**

- O modo vale para todas as entidades da unidade de persistência, inclusive as criadas depois.
- `SQLServerDialect.supportsAlterColumnType()` é `true`, e `getAlterColumnTypeString` emite `alter column <coluna> <tipo>` sem `not null`. Numa tabela existente com coluna divergente, a alteração deixa a coluna anulável. Por isso o mapeamento abaixo é escolhido para coincidir exatamente com os metadados que o SQL Server devolve da tabela criada, e nenhuma reinicialização emite `alter column`.

## Comparação do mapeamento com a tabela existente

**Contexto.** Em cada subida, `ColumnDefinitions.hasMatchingType` e `hasMatchingLength` decidem se uma coluna existente diverge do mapeamento.

**Decisão:** declarar tipo, tamanho, precisão e escala de cada coluna de forma explícita, como em [`data-model.md`](./data-model.md).

**Evidência:**

- `hasMatchingType` aceita a coluna quando os códigos de tipo são equivalentes ou quando o nome do tipo do mapeamento, sem argumentos, é igual ao nome devolvido pelo banco. `datetime2`, `char`, `varchar`, `decimal` e `bigint` coincidem nos dois critérios.
- `hasMatchingLength` só compara tamanho quando o tipo do mapeamento tem argumentos: comprimento para texto (`char(1)` e `varchar(255)`) e precisão e escala para numérico (`decimal(18,2)`). Com os valores declarados iguais aos da tabela criada, as comparações batem.

**Consequências:** as reinicializações sobre a tabela criada pela aplicação não emitem DDL.

## Tipo de `data_hora`

**Contexto.** A spec define `data_hora` como `DATETIME2` com o instante em UTC, a mesma convenção do `criado_em` de `pagamento` na skill de Pagamentos.

**Alternativas:**

- **`Instant` sem ajuste**: o tipo JDBC preferido para `Instant` é `TIMESTAMP_UTC`, e `Dialect.columnType(TIMESTAMP_UTC)` usa `TIMESTAMP_WITH_TIMEZONE` quando o suporte a fuso é nativo. `SQLServerDialect.getTimeZoneSupport()` devolve `NATIVE`, e `columnType(TIMESTAMP_WITH_TIMEZONE)` é `datetimeoffset($p)`. A coluna sairia `datetimeoffset`.
- **`Instant` com `columnDefinition = "datetime2"`**: a coluna sai `datetime2`, e o valor continua tratado como instante.
- **`LocalDateTime`**: mapeia para `datetime2($p)`, mas o campo não carrega a informação de que o valor está em UTC.

**Decisão:** campo `Instant dataHora` com `@Column(name = "data_hora", nullable = false, columnDefinition = "datetime2")`.

**Evidência:**

- `SQLServerDialect.contributeTypes` registra `TimestampUtcAsJdbcTimestampJdbcType` para o código `TIMESTAMP_UTC`, com o comentário de que a leitura de `OffsetDateTime` de uma coluna `datetime2` falha. Esse tipo grava com `setTimestamp(index, Timestamp.from(instant), Calendar UTC)` e lê com `getTimestamp(index, Calendar UTC)`: o `datetime2` guarda o horário em UTC, independente do fuso da JVM.
- `ColumnDefinitions.appendColumnDefinition` escreve o tipo declarado em `columnDefinition` e acrescenta `not null` para coluna não anulável.
- Sem argumentos em `datetime2`, `hasMatchingLength` não compara precisão. A precisão é a padrão do SQL Server (7), a mesma do `DATETIME2` de `criado_em`.

**Consequências:** a coluna segue a convenção do domínio para instantes, e a conversão de fuso fica no tipo JDBC do dialeto, sem configuração global de fuso.

## Tipo de `valor`

**Contexto.** A spec define `valor` como `DECIMAL(18,2)`. O tipo JDBC padrão de `BigDecimal` é `NUMERIC`, e `Dialect.columnType(NUMERIC)` é `numeric($p,$s)`.

**Alternativas:**

- **`@JdbcTypeCode(SqlTypes.DECIMAL)`**: `Dialect.columnType(DECIMAL)` é `decimal($p,$s)`, e o `DecimalJdbcType` liga `BigDecimal`.
- **`columnDefinition = "decimal(18,2)"`**: fixa o texto da coluna fora de `precision`/`scale`.
- **Tipo padrão (`numeric(18,2)`)**: equivalente no SQL Server, mas `INFORMATION_SCHEMA.COLUMNS` reporta `numeric`, fora do critério de aceite.

**Decisão:** `@JdbcTypeCode(SqlTypes.DECIMAL)` com `@Column(precision = 18, scale = 2)`.

**Evidência:** `Dialect.columnType` (linhas 599–600 de `Dialect.java`) mapeia `NUMERIC` para `numeric($p,$s)` e `DECIMAL` para `decimal($p,$s)`; `SQLServerDialect.columnType` não sobrescreve nenhum dos dois.

**Consequências:** o tamanho continua declarado em `precision` e `scale`, que `hasMatchingLength` compara com o banco.

## Tipo de `tipo`

**Contexto.** A spec define o campo `tipo` da entidade como `char`, sem classe de enumeração, e a coluna como `CHAR(1)` com os valores `E` (entrada) e `S` (saída).

**Decisão:** campo `char tipo` com `@Column(name = "tipo", nullable = false, length = 1, check = @CheckConstraint(constraint = "tipo in ('E','S')"))`.

**Evidência:**

- `CharacterJavaType` descreve `char`/`Character` e devolve `1` em `getDefaultSqlLength`. O tipo JDBC é `CHAR`, e `Dialect.columnType(CHAR)` é `char($l)`; `SQLServerDialect.columnType` não o sobrescreve. A coluna sai `char(1)`.
- Sem enumeração, o Hibernate não gera restrição de valores para a coluna. O `jakarta.persistence-api` 3.2.0 gerenciado pelo Spring Boot 4.1.1 (`jakarta-persistence.version`) tem `Column.check`, do tipo `CheckConstraint[]` (atributos `name`, `constraint` e `options`). `StandardTableExporter.applyTableCheck` inclui as restrições da coluna no `create table`. A anotação `org.hibernate.annotations.Check` tem o mesmo efeito, mas está marcada como `@deprecated` em favor de `Column.check`.
- `hasMatchingType` e `hasMatchingLength` comparam `char` e comprimento `1` com os metadados da coluna criada e não emitem `alter column` na reinicialização.

**Consequências:** o banco recusa um valor diferente de `E` e `S` em `tipo`, e um texto com mais de um caractere excede a coluna. A restrição é criada só com a tabela.

## Identificador

**Contexto.** A spec define `id` como `BIGINT IDENTITY` gerado pelo banco.

**Decisão:** `Long id` com `@GeneratedValue(strategy = GenerationType.IDENTITY)`.

**Evidência:** `SQLServerIdentityColumnSupport` estende `AbstractTransactSQLIdentityColumnSupport`, que mantém o tipo da coluna na definição de identidade (`bigint identity not null`) e lê a chave gerada com `select scope_identity()`.

**Consequências:** o banco gera o `id` também nas linhas gravadas diretamente por SQL, como nos critérios de aceite.

## Código da entidade

**Contexto.** O projeto declara Lombok como processador de anotações (`AGENTS.md`, decisão E7), e a aplicação não tem outras classes além de `DemooutboxinboxApplication`.

**Decisão:** `@Getter`, `@Setter` e `@NoArgsConstructor` do Lombok na `MovimentoEntity`, no subpacote `br.com.db1.demooutboxinbox.movimento`.

**Evidência:** `maven-compiler-plugin` do `pom.xml` com `lombok` em `annotationProcessorPaths` nas compilações principal e de testes. O `@SpringBootApplication` em `br.com.db1.demooutboxinbox` faz a varredura de entidades desse pacote e dos subpacotes.

**Consequências:** `@Data` fica de fora: o `equals`/`hashCode` sobre todos os campos não é adequado a entidade JPA com `id` gerado pelo banco.
