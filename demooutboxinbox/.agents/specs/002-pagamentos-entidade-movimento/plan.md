# Plano: Entidade de movimento

## Stack e estrutura

A stack e a estrutura são as do `AGENTS.md`. O Spring Data JPA (Hibernate 7.4.5, gerenciado pelo `spring-boot-starter-parent` 4.1.1) e a conexão com o SQL Server local já estão configurados. A unidade cria uma classe Java e altera um arquivo de configuração:

- `src/main/java/br/com/db1/demooutboxinbox/movimento/MovimentoEntity.java`: entidade JPA mapeada para a tabela `movimento`.
- `src/main/resources/application.properties`: recebe `spring.jpa.hibernate.ddl-auto=update`.

O `pom.xml` não muda. A `DemooutboxinboxApplication` não muda: o `@SpringBootApplication` encontra a entidade pela varredura do subpacote.

## Decisões técnicas

Uma linha por veredito. Contexto, alternativas e evidências estão em [`research.md`](./research.md).

- **Criação da tabela:** `spring.jpa.hibernate.ddl-auto=update`, que cria a tabela ausente e nunca remove tabela existente.
- **Mapeamento exato:** nome da tabela, nomes, tipos, tamanhos, precisão e escala de todas as colunas são declarados na entidade e coincidem com os metadados da tabela criada, para que as reinicializações não emitam `alter column`.
- **`data_hora`:** campo `Instant` com `columnDefinition = "datetime2"`. O dialeto do SQL Server grava e lê o instante em UTC.
- **`valor`:** campo `BigDecimal` com `@JdbcTypeCode(SqlTypes.DECIMAL)`, precisão 18 e escala 2, para gerar `decimal(18,2)`.
- **`tipo`:** campo `char` com comprimento 1 e `@CheckConstraint` `tipo in ('E','S')` no `@Column`, sem classe de enumeração. O Hibernate gera `char(1)` com a restrição de verificação.
- **`id`:** campo `Long` com `@GeneratedValue(strategy = GenerationType.IDENTITY)`, que gera `bigint identity`.
- **Código da entidade:** Lombok `@Getter`, `@Setter` e `@NoArgsConstructor`, no subpacote `br.com.db1.demooutboxinbox.movimento`.

O `application.properties` resultante tem este conteúdo:

```properties
spring.application.name=demooutboxinbox

spring.config.import=optional:file:../.env[.properties]

spring.datasource.url=jdbc:sqlserver://localhost:${MSSQL_PORT:1433};databaseName=master;trustServerCertificate=true
spring.datasource.username=sa
spring.datasource.password=${MSSQL_SA_PASSWORD}

spring.jpa.hibernate.ddl-auto=update

management.endpoint.health.show-components=always
```

## Modelo de dados

Tabela `movimento` com `id` `bigint identity`, `data_hora` `datetime2`, `tipo` `char(1)` (`E` ou `S`), `descricao` `varchar(255)` e `valor` `decimal(18,2)`, todas não anuláveis. Colunas, mapeamento JPA de cada campo e restrições em [`data-model.md`](./data-model.md).

## Contratos externos

Sem `contracts/`. A unidade não cria endpoint, evento nem mensagem.

## Interface

Sem `ui/`. A unidade é só de backend.

## Estratégia de testes

A decisão D6 de `discovery-answers.md` e o `AGENTS.md` definem que não há testes unitários nem de integração além do `contextLoads`. A unidade não cria teste nem infraestrutura de teste. A verificação dos critérios de aceite usa o que já existe:

- **`contextLoads`:** `./mvnw test` a partir de `demooutboxinbox/`, com o `../compose.yaml` no ar. O contexto passa a executar a geração de esquema do Hibernate, e o teste falha se a criação da tabela falhar. A execução cria a tabela `movimento` no banco `master` local.
- **Estrutura da tabela:** com a tabela ausente, `./mvnw spring-boot:run` a partir de `demooutboxinbox/`. Em seguida, a partir da raiz do repositório, consultas com `docker compose exec sqlserver /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "<senha do .env>"` sobre `INFORMATION_SCHEMA.COLUMNS`, `COLUMNPROPERTY(OBJECT_ID('movimento'), 'id', 'IsIdentity')` e `INFORMATION_SCHEMA.TABLE_CONSTRAINTS` conferem colunas, tipos, tamanhos, obrigatoriedade, identidade, chave primária e a restrição de `tipo`.
- **Gravação direta:** pelo mesmo `sqlcmd`, o `INSERT` do critério de aceite grava uma linha com `id` gerado. Um `INSERT` com `descricao` nula, outro com 256 caracteres em `descricao` e outro com `tipo = 'X'` são recusados pelo banco.
- **Preservação na reinicialização:** com a linha gravada, a aplicação é parada e iniciada de novo. A linha continua presente com os mesmos valores, e o log da subida não mostra `create table` nem `alter table` para `movimento`.
- **Outras tabelas:** a contagem de linhas de outra tabela do banco `master` que não é entidade é a mesma antes e depois da subida.

## Impacto na documentação autoritativa

Há desvio deliberado. O pedido desta unidade acrescenta ao domínio Pagamentos a entidade de movimento e a criação da tabela pelo Hibernate na subida. A documentação descreve só `pagamento` e as tabelas da outbox no banco e não menciona geração de esquema. Os documentos afetados são:

- **[`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md)**, seção "Entidades e dados": recebe a tabela `movimento` (colunas `id`, `data_hora`, `tipo`, `descricao` e `valor`, com tipos, obrigatoriedade e os valores `E` para entrada e `S` para saída em `tipo`), sem regra de uso, endpoint nem evento associados. A nota de manutenção da skill pede atualização quando entidade ou campo mudam.
- **[`.agents/skills/pagamentos/references/technical-dependencies.md`](../../skills/pagamentos/references/technical-dependencies.md)**: recebe a precondição da geração de esquema do Hibernate (`spring.jpa.hibernate.ddl-auto=update`), sem a qual a tabela `movimento` não existe. O item 6 continua válido: a tabela `pagamento` não tem mecanismo de criação definido.
- **[`AGENTS.md`](../../../AGENTS.md)**, seção "Convenções de arquitetura": o banco único passa a listar `movimento` ao lado de `pagamento` e das tabelas da outbox, e a convenção de esquema registra que as tabelas das entidades JPA são criadas pelo Hibernate na inicialização com `ddl-auto=update`, sem ferramenta de migração.

A skill [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md) não muda: as tabelas da outbox não são entidades JPA e continuam fora da geração de esquema do Hibernate.
