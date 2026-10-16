---
name: mensageria-inbox
description: Esta é a documentação autoritativa do domínio Mensageria Inbox, o lado consumidor da entrega atômica de mensagens entre aplicações com bancos próprios. Cobre envelope padrão, consumidor com ack manual, inbox e dedup por messageId, serialização do processamento por aggregateKey, transação local do efeito mais inbox, retry com backoff, DLQ para mensagem veneno e para as seguintes da mesma chave, métricas de consumo e propagação de correlationId/traceId. Consulte ao implementar ou alterar qualquer um desses termos.
metadata:
  author: clovis-cli
  type: domain-skill
---

# Mensageria Inbox

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão (novo critério, nova semântica, novo campo de contrato). Refactor técnico que preserva a regra (renomear, extrair, reorganizar) não pede atualização. Se a skill disser X e o código ou o estado do projeto fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

O Mensageria Inbox é o módulo do lado **consumidor** da entrega atômica de mensagens entre duas aplicações, cada uma com o seu banco, sem transação distribuída (sem XA, sem JTA, sem gerenciador de transação externo). Ele recebe mensagens do broker publicadas de forma **at-least-once** (podem chegar repetidas) e garante que o efeito de negócio seja aplicado **uma única vez**:

1. O broker entrega a mensagem ao consumidor.
2. O consumidor grava o efeito de negócio **e** o registro na **inbox** na mesma transação local do seu banco.
3. Só depois do commit o consumidor confirma a mensagem (ack manual).

O dedup por `messageId` na inbox transforma a entrega at-least-once em efeito exatamente-uma-vez. O módulo é isolado e autossuficiente: carrega a sua própria definição do envelope padrão, e a base é reaproveitada por um segundo caso (coordenação de vários passos com compensação); esse caso está fora deste domínio.

O módulo cobre: envelope (leitura), consumidor com ack manual, serialização por `aggregateKey`, inbox, dedup, retry/backoff, DLQ e métricas de consumo.

## Fronteira do domínio

- **Pertence a este domínio:** leitura do envelope, consumidor, serialização por `aggregateKey`, inbox, dedup por `messageId`, ack, retry com backoff, DLQ, métricas de consumo e propagação de `correlationId`/`traceId` nos logs do consumo.
- **Não pertence:** o efeito de negócio (por exemplo, o lançamento de extrato), o conteúdo e a validação semântica dos payloads, a outbox, o relay, o lease, a publicação e a verificação independente das invariantes entre os dois bancos. O módulo entrega o payload ao tratador da aplicação sem interpretá-lo; quem valida o conteúdo é a aplicação consumidora.
- O registro na inbox participa da transação local aberta para o efeito de negócio; o módulo não abre transação separada para ele.

## Regras de negócio

Numeração própria desta skill (R1, R2, ...).

### Envelope e contrato

- **R1.** Toda mensagem recebida traz o envelope padrão com os campos da seção "Entidades e dados". `messageId` identifica a mensagem de forma única e é a chave de dedup.
- **R2.** `aggregateKey` é a chave de ordenação. `idempotencyKey` é a chave de idempotência do efeito de negócio, fornecida pelo produtor e entregue ao tratador da aplicação; o dedup do módulo usa apenas `messageId`.
- **R3.** `schemaVersion` versiona o contrato do payload; o módulo o entrega ao tratador, que o usa para interpretar o payload.

### Inbox e dedup

- **R4.** Para cada mensagem recebida, o consumidor grava o efeito de negócio **e** o registro na inbox (`message_id`) na **mesma transação local** e só então confirma a mensagem (ack manual). O ack nunca acontece antes do commit.
- **R5.** Se o `messageId` já estiver na inbox, a mensagem é um duplicado: é **descartada com ack**, sem reaplicar o efeito, e a métrica de duplicatas descartadas é incrementada.
- **R6.** Duas entregas simultâneas do mesmo `messageId` não podem aplicar o efeito duas vezes: a chave primária de `message_id` garante que só uma transação confirma, e a violação de unicidade da outra é tratada como duplicata (ack e descarte), não como falha a ser repetida.
- **R7.** Se o consumidor cair entre o commit e o ack, o broker reentrega a mensagem; a inbox detecta o `messageId` e descarta; resta um único efeito.

### Ordem

- **R8.** O consumidor aplica as mensagens do mesmo `aggregateKey` na ordem em que o broker as entrega; o módulo não reordena. Para que threads diferentes não troquem essa ordem, o consumidor **serializa o processamento por `aggregateKey` dentro do processo**: cada chave tem a sua fila de execução em memória e só uma mensagem da chave é processada por vez. Quando uma thread já processa uma mensagem da chave, a seguinte da mesma chave aguarda o fim da anterior (commit e ack, ou falha). Chaves diferentes são processadas em paralelo. Não existe exigência de ordem global entre `aggregateKey` diferentes.
- **R8a.** A fila de execução por chave respeita o limite de mensagens em voo do consumidor (R17): mensagens aguardando a vez da sua chave contam como em voo, e a memória do consumidor continua limitada.

### Retry, backoff e DLQ

- **R9.** Falha no processamento (a transação do efeito falha, ou o payload é inválido) segue **política de retry com backoff**. A transação com falha é revertida, portanto nada é gravado na inbox e a nova entrega é tratada como mensagem nova.
- **R10.** Depois de um **limite configurável de tentativas**, a mensagem vai para a **DLQ**. A mensagem inválida ("veneno") não bloqueia as mensagens de **outras chaves**: elas seguem sendo processadas enquanto ela espera suas tentativas ou já está na DLQ.
- **R10a.** Durante o retry com backoff de uma mensagem, a **sua chave espera**: as mensagens seguintes do mesmo `aggregateKey` só são processadas depois que a anterior é aplicada ou vai para a DLQ, o que preserva a ordem (R8).
- **R10b.** Depois que uma mensagem vai para a DLQ por exceder o limite de tentativas, as **mensagens seguintes da mesma `aggregateKey` também vão para a DLQ**, para revisão manual. Aplicá-las pularia a mensagem perdida e quebraria a ordem da chave.
- **R11.** A DLQ é uma fila declarada pela aplicação, junto da fila principal (que é quorum, durável).

### Dialeto e portabilidade

- **R12.** O acesso à inbox é isolado por **dialeto** de banco. Onde a aplicação consumidora usa H2 em arquivo, o dialeto H2 é o utilizado: a inbox não depende de recursos exclusivos do SQL Server.
- **R13.** O módulo **não depende de framework de aplicação**; cada framework entra por um adaptador fino.
- **R14.** O módulo conhece o broker apenas por uma **interface de consumo**. Trocar de broker exige somente um novo adaptador. O adaptador RabbitMQ é o único que conhece detalhes do broker (filas quorum, ack manual).

### Observabilidade

- **R15.** O módulo expõe, via Micrometer, em formato consumível pela operação, as métricas de consumo: **duplicatas descartadas**, **mensagens na DLQ** e **tentativas de retry** de processamento. Elas são observação para análise posterior; **nenhuma tem valor-meta**.
- **R16.** `correlationId` e `traceId` recebidos nos headers da mensagem são **propagados nos logs** do consumo, de ponta a ponta. Os logs são estruturados e trazem `messageId`, `correlationId` e `aggregateKey`. `correlationId` liga as mensagens do mesmo processo (e é o campo que o caso de coordenação de vários passos reutiliza).

### Requisitos não funcionais com efeito de negócio

- **R17.** O consumidor **não cresce em memória sem limite** (RSS estável sob carga): limita as mensagens em voo. Os limites de heap e de memória direta são explícitos.
- **R18.** Nenhum número de latência ou throughput é meta do domínio.

## Fluxos e ciclo de vida

### Fluxo feliz

1. O consumidor recebe a mensagem do broker.
2. Abre a transação local, grava o efeito de negócio e o `message_id` na inbox, e faz commit.
3. Dá ack ao broker.
4. Registra as métricas de consumo.

### Ciclo de uma mensagem recebida

`recebida` → `aguardando a vez da chave` (outra mensagem do mesmo `aggregateKey` em processamento) → (a) `aplicada e confirmada` (efeito + inbox gravados, ack dado); (b) `duplicada` (`messageId` já na inbox: ack e descarte); (c) `em retry` (falha, transação revertida, nova entrega com backoff, chave em espera); (d) `na DLQ` (limite de tentativas excedido, ou mensagem seguinte de uma chave que já teve mensagem na DLQ).

### Comportamento esperado sob falhas

| Falha | Comportamento esperado |
|---|---|
| Mensagem repetida (por reentrega do broker ou republicação) | A inbox detecta o `messageId`, a mensagem é descartada com ack e existe um único efeito |
| Consumidor cai entre commit e ack | O broker reentrega; a inbox detecta o `messageId` e descarta; um único efeito |
| Falha na transação do efeito | Transação revertida, nada na inbox; retry com backoff |
| Mensagem inválida (veneno) | A chave espera durante o retry; após o limite de tentativas a mensagem vai para a DLQ, as seguintes da mesma chave também vão para a DLQ, e as demais chaves continuam sendo processadas |
| Dois eventos do mesmo `aggregateKey` | Aplicados na ordem em que chegaram, que é a ordem em que ocorreram, um de cada vez |
| Duas mensagens do mesmo `aggregateKey` em threads diferentes | A segunda aguarda na fila de execução da chave até a primeira terminar (commit e ack, ou falha) |

## Entidades e dados

### Envelope padrão (todas as mensagens)

| Campo | Tipo | Descrição |
|---|---|---|
| `messageId` | UUID | Identificador único da mensagem; chave de dedup |
| `correlationId` | UUID | Liga mensagens do mesmo processo |
| `idempotencyKey` | string | Chave de idempotência do efeito de negócio |
| `aggregateKey` | string | Chave de ordenação |
| `type` | string | Tipo do evento (por exemplo, `PagamentoRecebido`) |
| `schemaVersion` | int | Versão do contrato do payload |
| `occurredAt` | ISO-8601 UTC | Momento do fato de negócio |
| `payload` | bytes | Dados do evento, opacos para o módulo |

O consumidor lê os campos do envelope dos headers AMQP (`messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion` e `occurredAt` em ISO-8601 UTC com microssegundos) e o `payload` do corpo da mensagem, sem alteração. O metadado de propagação `traceId` chega no header `traceId`. As properties AMQP `message_id`, `correlation_id` e `type` espelham os headers para ferramentas de operação e não são a fonte da leitura. Contrato completo em [`contracts/mensagem-publicada.md`](../../specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md) da spec `001-mensageria-outbox-implementacao-modulo`.

### Tabela `inbox` (banco do consumidor)

| Coluna | Tipo | Observação |
|---|---|---|
| `message_id` | `UNIQUEIDENTIFIER` PK | A unicidade é o dedup |
| `consumed_at` | `DATETIME2` NOT NULL, padrão `SYSUTCDATETIME()` | Momento do consumo |

No H2, os tipos do SQL Server têm equivalentes (identificador único como `UUID`, `DATETIME2` como `TIMESTAMP`); a inbox mantém a mesma chave primária. O esquema é um esboço de PoC: nomes, tipos e índices podem ser ajustados com o DBA. A aplicação consumidora pode manter uma segunda barreira de unicidade no próprio dado de negócio, além da inbox.

### Filas

- Fila principal: `quorum`, durável, declarada pela aplicação, com os mesmos argumentos em toda aplicação que a declara.
- DLQ: fila declarada pela aplicação, destino das mensagens que excederam o limite de tentativas.

## Restrições e validações

- Sem XA, sem JTA, sem gerenciador de transação externo; um banco/schema separado por aplicação e nenhuma transação local entre produtor e consumidor.
- Filas quorum e ack manual no consumidor são obrigatórios.
- O ack só ocorre depois do commit da transação local do consumidor.
- O módulo não valida o conteúdo do payload; mensagem cujo payload a aplicação não consegue processar segue o caminho de retry e DLQ (R9, R10).
- Ordem apenas por `aggregateKey`, garantida pela serialização por chave no processo (R8); nenhuma garantia de ordem global.
- Credenciais de banco e broker nunca ficam versionadas.

## Parâmetros configuráveis

Os parâmetros abaixo são configuráveis; nenhum valor padrão é definido pelas fontes e os valores são escolhidos e registrados na implementação:

- limite de tentativas do consumidor antes da DLQ e a política de backoff;
- limite de mensagens em voo do consumidor;
- limites de heap e de memória direta do processo do consumidor.

O repositório não declara variáveis de ambiente específicas deste domínio.

## Integrações e dependências externas

- **H2 em arquivo:** banco da inbox onde os recursos exclusivos do SQL Server não são necessários.
- **RabbitMQ:** broker com filas quorum e ack manual, acessado pelo adaptador de broker.
- **Micrometer:** métricas.

As dependências técnicas e as precondições de ambiente do domínio estão em [references/technical-dependencies.md](references/technical-dependencies.md).

## Pontos em aberto

- Se o código da PoC é descartável ou evolui para o starter interno é uma pergunta sem decisão; não bloqueia o domínio, e o isolamento do módulo (sem framework de aplicação, broker por interface) o mantém reaproveitável.
- A compatibilidade de Micrometer e Testcontainers com a versão mínima de plataforma deve ser verificada na implementação.

## Manutenção

- Mudança **deliberada** de comportamento, com a intenção conhecida: atualize esta skill.
- Divergência **semântica** entre esta skill e o código sem decisão registrada que a resolva: trate como lacuna e escale para decisão humana; não ajuste a skill nem o código por conta própria.
- Refactor que preserva a regra: não altere a skill.
