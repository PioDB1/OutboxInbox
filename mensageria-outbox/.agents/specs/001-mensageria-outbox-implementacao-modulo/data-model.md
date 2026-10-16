# Modelo de dados: Mensageria Outbox

Tabelas no banco de origem da aplicação produtora (SQL Server 2019). O script `schema.sql` do módulo é idempotente (`IF OBJECT_ID(...) IS NULL`) e instalado por `OutboxSchemaInstaller`. Os nomes, tipos e índices partem do esboço da skill `mensageria-outbox` e acrescentam o que as regras R5, R15 e R18 exigem.

## Entidades

### `outbox`

| Coluna | Tipo | Nulo | Observação |
|---|---|---|---|
| `seq` | `BIGINT IDENTITY(1,1)` | não | PK clusterizada; atribuído antes do commit |
| `message_id` | `UNIQUEIDENTIFIER` | não | vira `messageId` |
| `correlation_id` | `UNIQUEIDENTIFIER` | não | vira `correlationId` |
| `idempotency_key` | `NVARCHAR(200)` | não | vira `idempotencyKey` |
| `aggregate_key` | `NVARCHAR(100)` | não | chave de ordenação |
| `type` | `NVARCHAR(100)` | não | tipo do evento; routing key na publicação |
| `schema_ver` | `SMALLINT` | não | versão do contrato do payload |
| `occurred_at` | `DATETIME2(6)` | não | momento do fato de negócio, em UTC |
| `trace_id` | `NVARCHAR(64)` | não | `traceId` informado pela aplicação |
| `payload` | `VARBINARY(MAX)` | não | bytes do payload, sem interpretação |
| `created_at` | `DATETIME2(6)` | não | padrão `SYSUTCDATETIME()`; momento da gravação |
| `rv` | `ROWVERSION` | não | limite seguro da estratégia de ponto de leitura |
| `sent_at` | `DATETIME2(6)` | sim | preenchido somente pela estratégia claim, no confirm positivo |

Estado da linha por estratégia:

| Estado | Claim | Ponto de leitura |
|---|---|---|
| `pendente` | `sent_at IS NULL` | `rv > last_rv` |
| `enviada` | `sent_at IS NOT NULL` | `rv <= last_rv` |
| `removida` | linha apagada pela retenção | linha apagada pela retenção |

### `outbox_lease`

| Coluna | Tipo | Nulo | Observação |
|---|---|---|---|
| `lease_name` | `VARCHAR(50)` | não | PK; valor fixo `relay` |
| `holder_id` | `NVARCHAR(100)` | não | identificador da instância detentora |
| `expires_at` | `DATETIME2(6)` | não | expiração, no relógio do banco |

### `outbox_read_point`

| Coluna | Tipo | Nulo | Observação |
|---|---|---|---|
| `relay_name` | `VARCHAR(50)` | não | PK; valor fixo `relay` |
| `last_rv` | `BINARY(8)` | não | último `rv` do prefixo contíguo publicado; inicial `0x0000000000000000` |
| `updated_at` | `DATETIME2(6)` | não | padrão `SYSUTCDATETIME()` |

## Restrições

- `UNIQUE (outbox.message_id)`: um `messageId` por linha.
- Uma única linha em `outbox_lease` e uma em `outbox_read_point` (PK por nome fixo, criadas pelo script).
- `outbox.payload` nunca é lido por consulta de pendentes, de métricas ou de retenção.

## Índices

- `PK_outbox (seq)`, clusterizado: ordem de leitura da estratégia claim.
- `IX_outbox_pendente (seq) WHERE sent_at IS NULL`, filtrado: seleção de pendentes da estratégia claim.
- `IX_outbox_aggregate (aggregate_key, seq)`: sonda de linha anterior do mesmo `aggregateKey` (claim).
- `UX_outbox_rv (rv)`, único: leitura por faixa de `rv` (ponto de leitura) e limpeza em lote.
- `UX_outbox_message_id (message_id)`, único.

## Observações

- `rv` é um contador do banco; uma linha atualizada (marcação `sent_at` da estratégia claim) recebe novo `rv`. Cada implantação usa uma única estratégia, e a estratégia de ponto de leitura nunca atualiza a outbox.
- `MIN_ACTIVE_ROWVERSION()` vale para o banco inteiro: transações abertas em qualquer tabela com `rowversion` rebaixam o limite seguro, sem efeito sobre a correção.
- Trocar a estratégia de uma implantação com linhas pendentes exige esvaziar a outbox ou reiniciar o ponto de leitura para zero; a estratégia de ponto de leitura reenvia as linhas com `rv` acima do ponto, o que o at-least-once admite.
