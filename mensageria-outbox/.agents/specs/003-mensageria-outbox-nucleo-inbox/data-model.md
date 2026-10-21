# Modelo de dados: núcleo da inbox

A inbox é uma tabela no banco da aplicação consumidora, criada por script idempotente do dialeto antes de a aplicação subir (a auto-configuração não instala o esquema). Fonte do esboço: `spec-caso-a-outbox-inbox.md` §5 e skill [`mensageria-inbox`](../../skills/mensageria-inbox/SKILL.md), seção "Tabela `inbox`".

## Tabela `inbox`

| Coluna | SQL Server | H2 | Observação |
|---|---|---|---|
| `message_id` | `UNIQUEIDENTIFIER NOT NULL`, chave primária `PK_inbox` | `UUID NOT NULL`, chave primária | A unicidade é o dedup (R5, R6) |
| `consumed_at` | `DATETIME2 NOT NULL`, padrão `SYSUTCDATETIME()` (`DF_inbox_consumed_at`) | `TIMESTAMP NOT NULL`, padrão `CURRENT_TIMESTAMP` | Instante do consumo em UTC |

No SQL Server a estrutura é a do `inbox` do `sql/outbox.sql` do `demooutboxinbox`, e o padrão grava o instante em UTC. No H2 o padrão `CURRENT_TIMESTAMP` grava o instante na zona da JVM; por isso `H2InboxDialect.insert` informa `consumed_at` com o instante UTC (`LocalDateTime` em UTC, como o `SqlServerOutboxDialect` faz com as datas), e o padrão da coluna só vale para uma inserção que não passe pelo dialeto.

## Acesso (`InboxDialect`)

| Operação | SQL Server | H2 |
|---|---|---|
| Existe `message_id` | `SELECT 1 FROM inbox WHERE message_id = ?` (UUID como texto) | `SELECT 1 FROM inbox WHERE message_id = ?` (UUID por `setObject`) |
| Inserir | `INSERT INTO inbox (message_id) VALUES (?)` | `INSERT INTO inbox (message_id, consumed_at) VALUES (?, ?)` (`consumed_at` em UTC) |
| Violação de chave primária | `SQLException.getErrorCode()` 2627 ou 2601 | `SQLException.getSQLState()` `23505` |

No SQL Server `consumed_at` recebe o padrão da coluna; a inserção só informa `message_id`.

## Scripts de esquema

- `core/src/main/resources/br/com/db1/poc/inbox/schema/sqlserver/schema.sql` — `IF OBJECT_ID(N'inbox', N'U') IS NULL BEGIN CREATE TABLE inbox (...) END;`, lotes separados por `GO`.
- `core/src/main/resources/br/com/db1/poc/inbox/schema/h2/schema.sql` — `CREATE TABLE IF NOT EXISTS inbox (...)`, lotes separados por `GO`.
- `InboxSchemaInstaller.install(DataSource, InboxDialect)` lê o script do dialeto e executa cada lote; pode ser executado repetidamente.

## Estado em memória (fora do banco)

| Estrutura | Conteúdo | Ciclo de vida |
|---|---|---|
| Fila por chave (`KeyedExecutor`) | tarefas das mensagens que aguardam a vez da `aggregateKey` | removida quando a fila esvazia (R8a) |
| Chaves bloqueadas (`InMemoryBlockedKeys`) | `aggregateKey` de toda chave com mensagem na DLQ | até o fim do processo; sem liberação (P7 de `pendencias.md`) |
| Contagem de tentativas | tentativas feitas pela mensagem em curso | descartada ao fim da mensagem (aplicada, duplicata ou DLQ) |
