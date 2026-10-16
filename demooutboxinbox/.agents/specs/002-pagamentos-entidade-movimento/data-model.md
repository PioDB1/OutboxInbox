# Modelo de dados: Entidade de movimento

## Tabela `movimento`

Banco `master` do SQL Server local, esquema padrão do login `sa` (`dbo`). A tabela é gerada pelo Hibernate a partir da `MovimentoEntity`.

| Coluna | Tipo SQL Server | Nulo | Campo Java | Mapeamento JPA/Hibernate |
|---|---|---|---|---|
| `id` | `bigint identity` | não | `Long id` | `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`, `@Column(name = "id")` |
| `data_hora` | `datetime2` | não | `Instant dataHora` | `@Column(name = "data_hora", nullable = false, columnDefinition = "datetime2")` |
| `tipo` | `char(1)` | não | `char tipo` | `@Column(name = "tipo", nullable = false, length = 1, check = @CheckConstraint(constraint = "tipo in ('E','S')"))` |
| `descricao` | `varchar(255)` | não | `String descricao` | `@Column(name = "descricao", nullable = false, length = 255)` |
| `valor` | `decimal(18,2)` | não | `BigDecimal valor` | `@JdbcTypeCode(SqlTypes.DECIMAL)`, `@Column(name = "valor", nullable = false, precision = 18, scale = 2)` |

A classe tem `@Entity` e `@Table(name = "movimento")`, e todas as colunas têm o nome declarado em `@Column`.

## Valores de `tipo`

O campo é um `char` da própria entidade, sem classe de enumeração.

| Valor | Significado |
|---|---|
| `E` | entrada |
| `S` | saída |

## Restrições

- Chave primária em `id`, gerada pelo banco (`identity`).
- `NOT NULL` em todas as colunas.
- `CHECK (tipo in ('E','S'))`, declarado no `@Column` do campo `tipo` e gerado pelo Hibernate na criação da tabela.

## DDL esperada

Comando emitido pelo Hibernate quando a tabela não existe (a ordem das colunas e a formatação seguem a estratégia de ordenação do Hibernate e podem variar):

```sql
create table movimento (
  id bigint identity not null,
  data_hora datetime2 not null,
  descricao varchar(255) not null,
  tipo char(1) not null check (tipo in ('E','S')),
  valor decimal(18,2) not null,
  primary key (id)
);
```

## Índices

Nenhum além da chave primária. A unidade não define consulta sobre a tabela.
