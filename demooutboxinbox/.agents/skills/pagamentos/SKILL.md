---
name: pagamentos
description: Esta é a documentação autoritativa do domínio Pagamentos, fonte da verdade sobre a solicitação e o registro de pagamento e a publicação do evento PagamentoRecebido. Use ao implementar, alterar ou revisar o endpoint POST /pagamentos, a tabela pagamento, a tabela movimento e a MovimentoEntity, o endpoint POST /movimentos e seu parâmetro forcarFalha, o status RECEBIDO, cooperadoId, valor, o contrato PagamentoRecebido v1, o envelope da mensagem, a gravação atômica de pagamento e outbox e a publicação at-least-once no RabbitMQ.
metadata:
  author: clovis-cli
  type: domain-skill
---

# Pagamentos

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de propósito: campo, valor, validação, status, contrato do evento ou garantia de publicação. Quando a mudança no código for deliberada e a intenção for conhecida, a skill é atualizada para refletir o comportamento implementado. Refactor técnico que preserva o comportamento (renomear classes, mover pacotes, extrair helpers) não pede atualização. Se esta skill disser X e o código fizer Y sem decisão registrada que resolva a divergência, não ajuste nenhum dos lados por conta própria: trate como lacuna e escale para decisão humana.

## Visão geral do domínio

Pagamentos recebe a solicitação de pagamento de um cooperado, registra o pagamento e publica o fato `PagamentoRecebido` para outros sistemas. O problema de negócio resolvido é a **entrega atômica**: o pagamento registrado e a mensagem que o anuncia existem juntos ou não existem, usando apenas a transação local do banco da aplicação, sem transação distribuída entre banco e broker.

O domínio é o lado produtor do padrão outbox. Não existe consumidor da mensagem nesta aplicação: o evento publicado permanece na fila do broker. Consumo, deduplicação no consumidor (inbox), lançamento em extrato e reconciliação de invariantes estão fora do domínio.

## Regras de negócio

1. **Entrada.** A solicitação de pagamento chega por `POST /pagamentos` e informa o cooperado (`cooperadoId`) e o valor (`valor`). O identificador do pagamento (`pagamentoId`) é gerado por Pagamentos no registro, como UUID.
2. **Validação.** `cooperadoId` e `valor` são obrigatórios. Uma solicitação sem algum deles, com algum deles nulo ou com o corpo ilegível é recusada com `400 Bad Request` e nada é gravado. Não há outra validação de negócio sobre os dois campos: sinal, limites e número de casas decimais não são verificados. O valor registrado é o que a coluna `valor` (`DECIMAL(18,2)`) armazena; um valor que não cabe nessa coluna faz a gravação falhar e nada é gravado.
3. **Rastreamento da solicitação.** O `correlationId` e o `traceId` da mensagem vêm dos headers `X-Correlation-Id` e `X-Trace-Id` da requisição; quando um header está ausente ou vazio, Pagamentos gera o valor correspondente (UUID aleatório). Os chamadores enviam esses headers no formato padronizado: `X-Correlation-Id` como UUID e `X-Trace-Id` com até 64 caracteres. Pagamentos não valida nem corrige o formato dos headers; um valor fora desse padrão impede a montagem do envelope, e a solicitação falha sem gravar nada.
4. **Registro atômico.** Ao aceitar uma solicitação, Pagamentos grava a linha em `pagamento` e a mensagem `PagamentoRecebido` na outbox na **mesma transação local**. Se a transação falhar em qualquer ponto antes do commit, não existe nem o pagamento nem a linha da outbox.
5. **Status inicial.** Todo pagamento registrado nasce com status `RECEBIDO`. O domínio não define outras transições de status.
6. **Um evento por pagamento.** Cada pagamento registrado gera exatamente uma mensagem `PagamentoRecebido`, gravada na outbox na transação do registro. Solicitações repetidas com os mesmos dados geram pagamentos e mensagens distintos: a `idempotencyKey` da mensagem é o próprio `messageId`.
7. **Resposta.** Com a transação confirmada, a solicitação é respondida com `201 Created` e o pagamento registrado (`pagamentoId`, `cooperadoId`, `valor`, `status` e `criadoEm`).
8. **Publicação após o commit.** A publicação no broker só começa depois do commit da transação do registro e não faz parte da resposta à solicitação: a solicitação é concluída quando pagamento e outbox estão gravados, independentemente de o broker estar disponível.
9. **Mensagem enviada só após o confirm.** A mensagem é considerada enviada somente depois da confirmação positiva do broker (publisher confirm); só então a linha da outbox é marcada como enviada (`sent_at` preenchido). Enquanto não houver confirm, o envio é repetido com intervalo crescente, sem limite de tentativas e sem descarte.
10. **Recuperação de pendentes.** Na inicialização da aplicação, toda linha da outbox ainda não marcada como enviada é publicada novamente pelo mesmo envio com repetição. Isso cobre publicações interrompidas por queda da aplicação ou do broker.
11. **At-least-once.** A mesma mensagem pode ser publicada mais de uma vez (por exemplo, queda entre o confirm e a marcação de envio, ou o envio após o commit concorrendo com a recuperação). A duplicidade é esperada; quem consome deduplica pelo `messageId`. Pagamentos não tenta evitá-la.
12. **Ordem.** O `aggregateKey` é o `pagamentoId`. Como cada pagamento produz uma única mensagem (regra 6), não há sequência de eventos do mesmo agregado a ordenar, e não existe ordem global entre pagamentos.
13. **Nada pendente indefinidamente.** Com o broker disponível, nenhuma linha da outbox permanece sem publicação; durante a indisponibilidade do broker as mensagens se acumulam na outbox e são todas publicadas, sem perda, quando ele volta.

## Fluxos e ciclo de vida

### Solicitação e registro (fluxo feliz)

1. Chega `POST /pagamentos` com `cooperadoId` e `valor`, e opcionalmente os headers `X-Correlation-Id` e `X-Trace-Id`.
2. Pagamentos verifica a presença de `cooperadoId` e `valor`; sem algum deles, responde `400` e encerra sem gravar.
3. Pagamentos obtém o `correlationId` e o `traceId` dos headers, gerando os ausentes, e gera o `pagamentoId`.
4. Em uma única transação local:
   1. grava `pagamento` com status `RECEBIDO`;
   2. monta o envelope de `PagamentoRecebido` (seção "Entidades e dados") e grava a linha correspondente na outbox.
5. A transação é confirmada; a solicitação é respondida com `201` e o pagamento registrado.
6. Após o commit, a mensagem é entregue ao envio assíncrono, que publica no broker, aguarda o confirm e marca a linha como enviada.

### Falha na gravação

Se qualquer passo de 4 falhar antes do commit, a transação é desfeita: não existe pagamento nem linha na outbox, e nada é publicado.

### Falha na publicação

Com o broker indisponível, com rede instável ou sem fila ligada à routing key, a publicação falha, a linha continua pendente e o envio é repetido com intervalo crescente até o confirm. Se a aplicação parar antes disso, a recuperação da próxima inicialização publica a linha.

### Ciclo de vida

| Entidade | Estado | Significado |
|---|---|---|
| `pagamento` | `RECEBIDO` | pagamento registrado; estado único do domínio |
| linha da outbox | pendente (`sent_at` nulo) | mensagem gravada, ainda sem confirm do broker |
| linha da outbox | enviada (`sent_at` preenchido) | confirm recebido; a linha não é mais publicada |

## Entidades e dados

### `POST /pagamentos`

Requisição — corpo JSON:

| Campo | Tipo | Regra |
|---|---|---|
| `cooperadoId` | número inteiro | obrigatório |
| `valor` | número decimal | obrigatório |

Headers opcionais:

| Header | Uso |
|---|---|
| `X-Correlation-Id` | `correlationId` da mensagem, enviado como UUID; gerado (UUID aleatório) quando ausente ou vazio |
| `X-Trace-Id` | `traceId` da mensagem, enviado com até 64 caracteres; gerado (UUID aleatório) quando ausente ou vazio |

O formato dos headers é responsabilidade de quem chama e não tem tratamento específico no endpoint: um `X-Correlation-Id` que não é UUID ou um `X-Trace-Id` acima de 64 caracteres faz a solicitação falhar sem gravar nada.

Respostas:

| Status | Quando | Corpo |
|---|---|---|
| `201 Created` | pagamento e mensagem gravados na mesma transação confirmada | o pagamento registrado (tabela abaixo) |
| `400 Bad Request` | `cooperadoId` ou `valor` ausente ou nulo, ou corpo ilegível | nada é gravado |

Corpo da resposta `201`:

| Campo | Conteúdo |
|---|---|
| `pagamentoId` | UUID gerado para o pagamento |
| `cooperadoId` | o `cooperado_id` registrado |
| `valor` | o valor registrado, com duas casas decimais |
| `status` | `RECEBIDO` |
| `criadoEm` | o `criado_em` registrado, instante ISO-8601 em UTC |

### Tabela `pagamento` (SQL Server)

Estrutura fixa, sem evolução versionada de esquema:

```sql
CREATE TABLE pagamento (
  pagamento_id   UNIQUEIDENTIFIER PRIMARY KEY,
  cooperado_id   BIGINT           NOT NULL,
  valor          DECIMAL(18,2)    NOT NULL,
  status         VARCHAR(20)      NOT NULL,   -- RECEBIDO
  criado_em      DATETIME2        NOT NULL DEFAULT SYSUTCDATETIME()
);
```

| Coluna | Significado |
|---|---|
| `pagamento_id` | identificador do pagamento, UUID gerado por Pagamentos |
| `cooperado_id` | identificador numérico do cooperado que solicitou o pagamento |
| `valor` | valor do pagamento, com duas casas decimais |
| `status` | `RECEBIDO` |
| `criado_em` | instante do registro, em UTC |

### Tabela `movimento` (SQL Server)

Registra movimentações de entrada e saída, cada uma com data e hora, descrição e valor. A tabela é gerada a partir da entidade JPA `MovimentoEntity` na inicialização da aplicação quando ainda não existe; quando já existe, é mantida com as linhas gravadas. Movimentações são incluídas pelo endpoint `POST /movimentos`, descrito abaixo. O domínio não define evento para movimentações, e uma movimentação não tem vínculo com `pagamento` nem com cooperado.

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `BIGINT IDENTITY` | chave primária, número sequencial gerado pelo banco |
| `data_hora` | `DATETIME2` | obrigatória; data e hora da movimentação, instante em UTC |
| `tipo` | `CHAR(1)` | obrigatória; `E` (entrada) ou `S` (saída), garantido por restrição de verificação da coluna |
| `descricao` | `VARCHAR(255)` | obrigatória; até 255 caracteres |
| `valor` | `DECIMAL(18,2)` | obrigatória; duas casas decimais |

Na entidade, `tipo` é um campo `char`, sem classe de enumeração.

#### `POST /movimentos`

Inclui uma movimentação em uma transação local, confirmada ao final; qualquer falha anterior ao commit desfaz a transação e nada é gravado. A inclusão não grava mensagem na outbox nem publica evento.

Requisição — corpo JSON:

| Campo | Tipo JSON | Regra |
|---|---|---|
| `dataHora` | string | obrigatória; instante ISO-8601 em UTC (por exemplo `2026-10-16T13:45:00Z`) |
| `tipo` | string | obrigatório; um caractere, `E` (entrada) ou `S` (saída) |
| `descricao` | string | obrigatória; até 255 caracteres |
| `valor` | número decimal | obrigatório; registrado com duas casas decimais |

A única validação feita pelo endpoint é a presença dos quatro campos. `tipo` fora de `E` e `S`, `descricao` acima de 255 caracteres e `valor` que não cabe em `DECIMAL(18,2)` são recusados pelo banco na gravação; sinal e limites do valor e data futura não são verificados.

Parâmetro de consulta:

| Parâmetro | Tipo | Regra |
|---|---|---|
| `forcarFalha` | booleano | opcional, padrão `false`; recurso de demonstração: com `true`, a inclusão falha depois de o `INSERT` chegar ao banco e antes do commit, e a transação é desfeita |

Respostas:

| Status | Quando | Corpo |
|---|---|---|
| `201 Created` | movimentação gravada e transação confirmada | a movimentação registrada (tabela abaixo) |
| `400 Bad Request` | `dataHora`, `tipo`, `descricao` ou `valor` ausente ou nulo, corpo ilegível ou `forcarFalha` com valor que não é booleano | nada é gravado |
| `500 Internal Server Error` | falha forçada por `forcarFalha=true`, ou recusa do banco na gravação | nada é gravado |

Corpo da resposta `201`:

| Campo | Conteúdo |
|---|---|
| `id` | identificador numérico gerado pelo banco |
| `dataHora` | o `data_hora` registrado, instante ISO-8601 em UTC |
| `tipo` | `E` ou `S` |
| `descricao` | a descrição registrada |
| `valor` | o valor registrado, com duas casas decimais |

O `INSERT` desfeito pela falha forçada consome um valor de identidade que o SQL Server não devolve: a inclusão seguinte recebe um `id` com salto.

### Evento `PagamentoRecebido` (versão 1)

Payload, serializado como JSON em UTF-8:

```json
{
  "pagamentoId": "uuid",
  "cooperadoId": 123,
  "valor": "150.00"
}
```

| Campo | Tipo JSON | Regra |
|---|---|---|
| `pagamentoId` | string | UUID do pagamento registrado |
| `cooperadoId` | número inteiro | o `cooperado_id` do pagamento |
| `valor` | string | o valor do pagamento com exatamente duas casas decimais e ponto como separador (`"150.00"`) |

### Envelope da mensagem

Toda mensagem gravada na outbox carrega o envelope padrão. Para `PagamentoRecebido`:

| Campo | Tipo | Valor em Pagamentos |
|---|---|---|
| `messageId` | UUID | UUID aleatório novo para cada mensagem; chave de deduplicação do consumidor |
| `correlationId` | UUID | o header `X-Correlation-Id` da solicitação, ou UUID aleatório gerado quando ausente ou vazio; liga mensagens do mesmo processo |
| `idempotencyKey` | texto, até 200 caracteres | o `messageId` da própria mensagem, no formato canônico de UUID |
| `aggregateKey` | texto, até 100 caracteres | o `pagamentoId`, no formato canônico de UUID |
| `type` | texto, até 100 caracteres | `PagamentoRecebido` |
| `schemaVersion` | inteiro | `1` |
| `occurredAt` | instante ISO-8601 UTC | o instante em que o pagamento é registrado |
| `payload` | bytes | o JSON de `PagamentoRecebido` acima |
| `traceId` | texto, até 64 caracteres | o header `X-Trace-Id` da solicitação, ou UUID aleatório em texto gerado quando ausente ou vazio; propagado nos logs de ponta a ponta |

Todos os campos de texto são obrigatórios e não podem ser vazios; um envelope inválido impede a gravação e desfaz a transação do registro.

### Mensagem publicada no RabbitMQ

- **Exchange:** `mensageria.outbox`, tipo `direct`, durável.
- **Routing key:** o `type` do envelope, `PagamentoRecebido`.
- **Corpo:** os bytes do payload, sem alteração; `content_type` = `application/octet-stream`; `delivery_mode` = `2` (persistente).
- **Headers:** `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion` (inteiro), `occurredAt` (ISO-8601 UTC com microssegundos, por exemplo `2026-10-14T16:20:31.123456Z`) e `traceId`, todos obrigatórios. As properties AMQP `message_id`, `correlation_id` e `type` espelham os headers correspondentes.
- **Publicação obrigatória:** a mensagem é publicada com `mandatory=true`. Sem fila ligada à routing key, o broker devolve a mensagem, a publicação conta como falha e a linha permanece pendente.
- **Fila de destino:** `pagamentos.pagamento-recebido`, fila quorum (`x-queue-type=quorum`, durável, não exclusiva, sem auto-delete, nenhum outro argumento) ligada ao exchange `mensageria.outbox` com a routing key `PagamentoRecebido`, declarada por Pagamentos na inicialização. Sem consumidor na aplicação, as mensagens publicadas permanecem nessa fila.

## Restrições e validações

- `cooperadoId` e `valor` são obrigatórios; é a única validação de negócio da solicitação.
- `cooperadoId` é um número inteiro que cabe em `BIGINT`.
- `valor` é registrado em `DECIMAL(18,2)`; resposta e payload carregam o valor registrado, e no payload ele é sempre texto com exatamente duas casas.
- `status` só assume `RECEBIDO`.
- Os textos do envelope respeitam os limites da tabela: `idempotencyKey` até 200, `aggregateKey` até 100, `type` até 100 e `traceId` até 64 caracteres, nenhum vazio.
- A gravação na outbox exige uma transação aberta; gravar fora de transação é erro de programação e falha.
- Cada tentativa de publicação é registrada em log com as chaves de contexto `messageId`, `correlationId`, `aggregateKey` e `traceId`, para que a publicação de um pagamento seja rastreável.

## Integrações e dependências externas

- **SQL Server** — banco único da aplicação, com `pagamento`, `movimento` e as tabelas da outbox; a atomicidade depende de os dois serem gravados na mesma transação local desse banco.
- **RabbitMQ** — broker de destino, com publisher confirms, exchange `direct` e fila quorum.
- **Mecanismo de outbox compartilhado** — biblioteca corporativa que fornece o envelope, a gravação na outbox, o esquema das tabelas da outbox e o publicador com confirms; a publicação pelo relay por polling dessa biblioteca permanece desligada e o envio é feito pelo disparo assíncrono após o commit com recuperação na inicialização.

As precondições técnicas do domínio — o que precisa existir para o comportamento acima funcionar e o efeito da ausência de cada uma — estão em [references/technical-dependencies.md](references/technical-dependencies.md).
