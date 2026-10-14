# Checklist de requisitos: Mensageria Outbox

Rastreabilidade entre as regras da skill [`mensageria-outbox`](../../../skills/mensageria-outbox/SKILL.md) e as histórias de [`spec.md`](../spec.md). Cada regra aparece em ao menos uma história com critério de aceitação.

| Regra | Assunto | História |
|---|---|---|
| R1, R2, R3, R4 | Envelope, `aggregateKey`, `idempotencyKey`, `schemaVersion`, `occurredAt` | 1 |
| R5 | Metadados de propagação persistidos na inserção | 1, 7 |
| R6 | Inserção na mesma transação local | 1 |
| R7 | `seq` atribuído antes do commit | 3 |
| R8, R20 | Outbox, lease e relay em dialeto SQL Server | 1, 5 |
| R9, R10 | Publisher confirms e at-least-once | 2 |
| R11 | Ordem por `aggregateKey` | 3 |
| R12, R13 | Retry com backoff e acúmulo com broker fora | 4 |
| R14 | Relay reiniciável, estado no banco | 5 |
| R15 | Duas estratégias atrás de uma interface, nenhuma linha pulada | 3 |
| R16, R17 | ADR da estratégia e semântica de `min_active_rowversion()` | 3, 8 |
| R18, R19 | Lease e dois relays sem perda | 5 |
| R19a | Relay somente em banco primário/ativo | 5 |
| R21, R22 | Sem framework de aplicação; broker por interface | 2 |
| R23 | Retenção da outbox | 6 |
| R24, R25 | Métricas, `correlationId`/`traceId`, logs estruturados | 7 |
| R26 | Memória limitada | 9 |
| R27 | Nenhuma meta de latência ou throughput | 7 |

## Dependências técnicas

- [ ] Ambiente Docker (`compose.yaml`, `.env.example`, `.gitignore`) — história 9
- [ ] Testcontainers e Toxiproxy no módulo — história 9
- [ ] ADR `0001` e índice — história 8
- [ ] Logs estruturados no relay — história 7
- [ ] Scripts de esquema (outbox, lease, ponto de leitura) — história 1
- [ ] Limites de heap e de memória direta no teste de carga — história 9
- [ ] Credenciais fora do repositório — história 9
