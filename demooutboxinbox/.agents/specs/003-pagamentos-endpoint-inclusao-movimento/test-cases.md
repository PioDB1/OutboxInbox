# Casos de teste: Endpoint de inclusão de movimento

## Pré-condições

Valem para todos os casos, exceto quando o caso indicar outra coisa:

- O SQL Server e o RabbitMQ do `../compose.yaml` estão no ar. Para subi-los, execute `docker compose up -d` na raiz do repositório (`..`).
- O `../.env` da raiz define `MSSQL_SA_PASSWORD` com a senha do login `sa` do contêiner.
- Os comandos Maven rodam a partir de `demooutboxinbox/`. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.
- A aplicação está iniciada com `./mvnw spring-boot:run` a partir de `demooutboxinbox/` e o log mostra a inicialização concluída. Ela atende em `http://localhost:8081`.
- As chamadas HTTP são feitas com `curl` num shell POSIX (Git Bash no Windows), com `-i` para exibir o status e o corpo da resposta. Os casos se referem a isso como "envie".
- As consultas ao banco são feitas no banco `master` do SQL Server local com o login `sa`, numa sessão interativa do `sqlcmd` do contêiner, aberta a partir da raiz do repositório:

  ```sh
  docker compose exec sqlserver bash -c '/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -d master'
  ```

  Na sessão, cada comando é digitado e seguido de uma linha com `GO`. Os casos se referem a essa sessão como "execute no banco".
- "Contagem de movimentos" é o resultado de `SELECT COUNT(*) FROM movimento` executado no banco.
- Cada caso usa uma descrição própria (por exemplo `TC-14 falha forçada`) para localizar as linhas que ele grava.

## História 1: inclusão de movimentação

### TC-1 (obrigatório): inclusão de entrada responde `201` com a movimentação gravada

1. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T13:45:00Z","tipo":"E","descricao":"Depósito","valor":150.00}'
   ```

2. Anote o `id` da resposta.

**Esperado:** status `201` e corpo JSON com `id` numérico, `dataHora` `"2026-10-16T13:45:00Z"`, `tipo` `"E"`, `descricao` `"Depósito"` e `valor` `150.00`.

### TC-2 (obrigatório): a inclusão grava a linha em `movimento`

1. Execute o TC-1 e anote o `id` da resposta.
2. Execute no banco `SELECT id, data_hora, tipo, descricao, valor FROM movimento WHERE id = <id anotado>`.

**Esperado:** a consulta devolve uma linha com `data_hora` no instante `2026-10-16 13:45:00` UTC, `tipo` `E`, `descricao` `Depósito` e `valor` `150.00`.

### TC-3 (obrigatório): inclusão de saída com `forcarFalha=false` responde `201` e grava a linha

1. Envie:

   ```sh
   curl -i -X POST 'http://localhost:8081/movimentos?forcarFalha=false' \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T14:00:00Z","tipo":"S","descricao":"TC-3 saída","valor":42.10}'
   ```

2. Anote o `id` da resposta.
3. Execute no banco `SELECT tipo, descricao, valor FROM movimento WHERE id = <id anotado>`.

**Esperado:** status `201` com `tipo` `"S"` no corpo. A consulta devolve uma linha com `tipo` `S`, `descricao` `TC-3 saída` e `valor` `42.10`.

### TC-4 (recomendado): a resposta reflete o valor registrado quando o valor enviado tem mais de duas casas

1. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T14:10:00Z","tipo":"E","descricao":"TC-4 arredondamento","valor":10.555}'
   ```

2. Anote o `id` e o `valor` da resposta.
3. Execute no banco `SELECT valor FROM movimento WHERE id = <id anotado>`.

**Esperado:** status `201`. O `valor` da resposta tem duas casas decimais e é igual ao `valor` gravado na coluna, arredondado pelo SQL Server em `DECIMAL(18,2)`.

### TC-5 (obrigatório): o teste `contextLoads` passa

1. Pare a aplicação iniciada nas pré-condições.
2. Execute `./mvnw test` a partir de `demooutboxinbox/`.

**Esperado:** o build termina com sucesso e o relatório mostra `Tests run: 1, Failures: 0, Errors: 0` para `DemooutboxinboxApplicationTests`.

## História 2: recusa de inclusão incompleta

### TC-6 (obrigatório): campo obrigatório ausente responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie quatro requisições `POST http://localhost:8081/movimentos` com `Content-Type: application/json`, cada uma sem um dos campos:

   | Requisição | Corpo |
   |---|---|
   | sem `dataHora` | `{"tipo":"E","descricao":"TC-6","valor":1.00}` |
   | sem `tipo` | `{"dataHora":"2026-10-16T14:20:00Z","descricao":"TC-6","valor":1.00}` |
   | sem `descricao` | `{"dataHora":"2026-10-16T14:20:00Z","tipo":"E","valor":1.00}` |
   | sem `valor` | `{"dataHora":"2026-10-16T14:20:00Z","tipo":"E","descricao":"TC-6"}` |

3. Anote a contagem de movimentos de novo.

**Esperado:** as quatro requisições respondem `400`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-7 (obrigatório): campo obrigatório com `null` responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie quatro requisições `POST http://localhost:8081/movimentos` com `Content-Type: application/json`, cada uma com um dos campos em `null`:

   | Requisição | Corpo |
   |---|---|
   | `dataHora` nula | `{"dataHora":null,"tipo":"E","descricao":"TC-7","valor":1.00}` |
   | `tipo` nulo | `{"dataHora":"2026-10-16T14:30:00Z","tipo":null,"descricao":"TC-7","valor":1.00}` |
   | `descricao` nula | `{"dataHora":"2026-10-16T14:30:00Z","tipo":"E","descricao":null,"valor":1.00}` |
   | `valor` nulo | `{"dataHora":"2026-10-16T14:30:00Z","tipo":"E","descricao":"TC-7","valor":null}` |

3. Anote a contagem de movimentos de novo.

**Esperado:** as quatro requisições respondem `400`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-8 (obrigatório): corpo que não é JSON válido responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T14:40:00Z","tipo":"E",'
   ```

3. Anote a contagem de movimentos de novo.

**Esperado:** status `400`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-9 (obrigatório): `dataHora` fora do formato ISO-8601 responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"16/10/2026 14:50","tipo":"E","descricao":"TC-9","valor":1.00}'
   ```

3. Anote a contagem de movimentos de novo.

**Esperado:** status `400`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-10 (recomendado): `tipo` com mais de um caractere responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T15:00:00Z","tipo":"ES","descricao":"TC-10","valor":1.00}'
   ```

3. Anote a contagem de movimentos de novo.

**Esperado:** status `400`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-11 (obrigatório): `tipo` diferente de `E` e `S` é recusado pelo banco com `500`

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T15:10:00Z","tipo":"X","descricao":"TC-11","valor":1.00}'
   ```

3. Anote a contagem de movimentos de novo e execute no banco `SELECT COUNT(*) FROM movimento WHERE descricao = 'TC-11'`.

**Esperado:** status `500`. A contagem de movimentos do passo 3 é igual à do passo 1, e a consulta por `descricao` devolve `0`.

### TC-12 (obrigatório): `descricao` com 256 caracteres é recusada pelo banco com `500`

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d "{\"dataHora\":\"2026-10-16T15:20:00Z\",\"tipo\":\"E\",\"descricao\":\"$(printf 'a%.0s' $(seq 1 256))\",\"valor\":1.00}"
   ```

3. Anote a contagem de movimentos de novo.

**Esperado:** status `500`. A contagem de movimentos do passo 3 é igual à do passo 1.

### TC-13 (recomendado): `valor` que não cabe em `DECIMAL(18,2)` é recusado pelo banco com `500`

1. Anote a contagem de movimentos.
2. Envie, com 17 dígitos na parte inteira do valor:

   ```sh
   curl -i -X POST http://localhost:8081/movimentos \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T15:30:00Z","tipo":"E","descricao":"TC-13","valor":12345678901234567.00}'
   ```

3. Anote a contagem de movimentos de novo e execute no banco `SELECT COUNT(*) FROM movimento WHERE descricao = 'TC-13'`.

**Esperado:** status `500`. A contagem de movimentos do passo 3 é igual à do passo 1, e a consulta por `descricao` devolve `0`.

## História 3: falha forçada e rollback

### TC-14 (obrigatório): `forcarFalha=true` responde `500` e não deixa a linha gravada

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST 'http://localhost:8081/movimentos?forcarFalha=true' \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T15:40:00Z","tipo":"E","descricao":"TC-14 falha forçada","valor":99.90}'
   ```

3. Anote a contagem de movimentos de novo e execute no banco `SELECT COUNT(*) FROM movimento WHERE descricao = 'TC-14 falha forçada'`.
4. Verifique o log da aplicação.

**Esperado:** status `500`. A contagem de movimentos do passo 3 é igual à do passo 1, e a consulta por `descricao` devolve `0`. O log registra a pilha da `IllegalStateException` da falha forçada, com o `id` gerado no `INSERT` desfeito.

### TC-15 (obrigatório): o `INSERT` desfeito consome um valor de identidade

1. Envie uma inclusão válida sem `forcarFalha`, com `descricao` `TC-15 antes`, e anote o `id` da resposta como `N`.
2. Envie a mesma inclusão com `?forcarFalha=true` e `descricao` `TC-15 falha forçada`, e confirme o status `500`.
3. Envie uma inclusão válida sem `forcarFalha`, com `descricao` `TC-15 depois`, e anote o `id` da resposta como `M`.
4. Execute no banco `SELECT id, descricao FROM movimento WHERE descricao LIKE 'TC-15%' ORDER BY id`.

**Esperado:** os passos 1 e 3 respondem `201` e `M` é maior que `N + 1`. A consulta devolve só as linhas `TC-15 antes` (`id` `N`) e `TC-15 depois` (`id` `M`): a linha da falha forçada chegou ao banco, consumiu um valor de identidade e foi desfeita pelo rollback.

### TC-16 (obrigatório): `forcarFalha` com valor não booleano responde `400` sem gravar

1. Anote a contagem de movimentos.
2. Envie:

   ```sh
   curl -i -X POST 'http://localhost:8081/movimentos?forcarFalha=talvez' \
     -H 'Content-Type: application/json' \
     -d '{"dataHora":"2026-10-16T15:50:00Z","tipo":"E","descricao":"TC-16","valor":1.00}'
   ```

3. Anote a contagem de movimentos de novo.

**Esperado:** status `400`. A contagem de movimentos do passo 3 é igual à do passo 1.
