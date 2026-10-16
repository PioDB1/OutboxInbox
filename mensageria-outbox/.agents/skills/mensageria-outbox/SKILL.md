---
name: mensageria-outbox
description: Esta é a documentação autoritativa do domínio Mensageria Outbox, o lado produtor da entrega atômica de mensagens entre aplicações com bancos próprios. Cobre envelope padrão, outbox, relay (estratégias claim e ponto de leitura), lease de relay único, publisher confirms, retry com backoff na publicação, retenção da outbox, métricas de publicação e propagação de correlationId/traceId. Consulte ao implementar ou alterar qualquer um desses termos.
metadata:
  author: clovis-cli
  type: domain-skill
---

# Mensageria Outbox

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão (novo critério, nova semântica, novo campo de contrato). Refactor técnico que preserva a regra (renomear, extrair, reorganizar) não pede atualização. Se a skill disser X e o código ou o estado do projeto fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

O Mensageria Outbox é o módulo do lado **produtor** da entrega atômica de mensagens entre duas aplicações, cada uma com o seu banco, sem transação distribuída (sem XA, sem JTA, sem gerenciador de transação externo). A atomicidade vem de uma transação **local** no banco do produtor, ligada ao broker por um relay:

1. A aplicação produtora grava seu dado de negócio **e** a mensagem (linha da **outbox**) na mesma transação local do seu banco.
2. Um **relay** lê a outbox e publica no broker, considerando a linha enviada somente depois da confirmação do broker (publisher confirm).
3. O broker entrega a mensagem ao consumidor, que fica fora deste domínio.

A publicação é **at-least-once**: a mesma mensagem pode ser publicada mais de uma vez, e o consumidor absorve as repetições pelo `messageId`. O módulo é isolado e autossuficiente: carrega a sua própria definição do envelope padrão, e a base é reaproveitada por um segundo caso (coordenação de vários passos com compensação), que reutiliza o envelope e o `correlationId`; esse caso está fora deste domínio.

O módulo cobre: envelope, inserção na outbox, interface do relay e as duas estratégias, lease de relay único, publicação com confirms, retry/backoff de publicação, retenção da outbox e métricas de publicação.

## Fronteira do domínio

- **Pertence a este domínio:** envelope, outbox, relay, lease, ponto de leitura, publicação no broker, retry de publicação, retenção, métricas de publicação e propagação de `correlationId`/`traceId` até a publicação.
- **Não pertence:** os dados de negócio das aplicações (por exemplo, o pagamento), o conteúdo e a validação dos payloads dos eventos, o endpoint de solicitação, o consumo, a inbox, o dedup, o retry de consumo, a DLQ e a verificação independente das invariantes entre os dois bancos. O módulo transporta o payload sem interpretá-lo.
- A inserção na outbox participa da transação local aberta pela aplicação; o módulo não abre transação própria para esse passo.

## Regras de negócio

Numeração própria desta skill (R1, R2, ...).

### Envelope e contrato

- **R1.** Toda mensagem trafega em um envelope padrão com os campos da seção "Entidades e dados". O módulo preenche e propaga o envelope; `messageId` identifica a mensagem de forma única e é a chave de dedup do consumidor.
- **R2.** `aggregateKey` é a chave de ordenação. `idempotencyKey` é a chave de idempotência do efeito de negócio, fornecida pelo produtor e transportada até o consumidor.
- **R3.** `schemaVersion` versiona o contrato do payload; o módulo o transporta sem interpretá-lo.
- **R4.** `occurredAt` é o momento do fato de negócio, em ISO-8601 UTC, e não o momento da publicação.
- **R5.** Tudo o que precisa atravessar o salto assíncrono (os campos do envelope, `traceId` e os demais metadados de propagação) precisa estar **persistido na inserção da outbox**, porque o relay publica depois, em outro momento e outra thread. O payload da outbox é o conteúdo mínimo do evento, guardado como bytes e publicado, sem alteração, como corpo da mensagem; os demais campos do envelope e o `traceId` são publicados em headers e properties AMQP (ver "Mensagem publicada").

### Outbox

- **R6.** A inserção na outbox acontece na mesma transação local do dado de negócio do produtor. Se a transação falhar antes do commit, nem o dado de negócio nem a linha da outbox existem.
- **R7.** Cada linha da outbox tem um `seq` crescente atribuído pelo banco. O `seq` é atribuído **antes do commit**: uma transação com `seq` menor pode confirmar depois de outra com `seq` maior. Por isso o relay não pode assumir que "tudo maior que o último `seq` lido" está completo (ver R15).
- **R8.** A outbox existe no banco de origem da aplicação produtora; ela, o lease e o relay usam o dialeto SQL Server (ver R20).

### Relay: publicação

- **R9.** O relay publica cada linha da outbox no broker usando **publisher confirms** e só considera a linha enviada **depois do confirm positivo**. Ausência de confirm, confirm negativo e erro de conexão contam como falha de publicação: a linha continua pendente.
- **R10.** A publicação é **at-least-once**. Se o relay cair depois de publicar e antes de registrar o envio, ao reiniciar ele publica a mesma mensagem de novo.
- **R11.** A **ordem por `aggregateKey`** é preservada: duas mensagens do mesmo `aggregateKey` são publicadas na ordem em que ocorreram. Uma mensagem de um `aggregateKey` não pode ser publicada enquanto uma anterior do mesmo `aggregateKey` ainda estiver pendente (incluindo a que falhou e aguarda nova tentativa). **Não** existe exigência de ordem global entre `aggregateKey` diferentes.
- **R12.** Falhas de publicação (broker indisponível, rede instável) seguem **retry com backoff**: os intervalos entre tentativas crescem, de modo que não haja tempestade de tentativas. O relay não descarta linha por falha de publicação; a linha permanece na outbox e é publicada quando o broker voltar. Nenhuma linha pode ficar indefinidamente sem publicação enquanto o broker estiver disponível.
- **R13.** Com o broker indisponível por um período, as mensagens acumulam na outbox (a aplicação produtora segue gravando) e, na volta do broker, **todas** são publicadas, sem perda.
- **R14.** O relay é **reiniciável a qualquer momento sem intervenção manual**. Todo estado de que ele precisa para retomar (linhas pendentes, lease, ponto de leitura quando a estratégia o usa) vive no banco, e não na memória do processo.

### Relay: duas estratégias atrás de uma interface

- **R15.** O relay é uma interface com **duas implementações**, ambas construídas e mantidas para comparação. Ambas resolvem o problema de R7: **nenhuma mensagem pode ser pulada**, mesmo quando transações confirmam fora da ordem de `seq`.

| | Estratégia 1: claim | Estratégia 2: ponto de leitura |
|---|---|---|
| Mecanismo | Seleciona as linhas pendentes com os recursos de bloqueio do SQL Server `UPDLOCK`, `READPAST` e `ROWLOCK`; publica; marca ou remove a linha | Lê até um limite seguro e persiste o ponto de leitura; o limite seguro usa uma coluna `rowversion` e a função `min_active_rowversion()` do SQL Server |
| Escrita no banco | `UPDATE`/`DELETE` por linha | Somente `INSERT`; limpeza em lote |
| Ordem por agregado | Exige cuidado ao reservar lotes: não reservar nem publicar uma mensagem de um `aggregateKey` com uma anterior ainda pendente ou reservada por outra instância | Natural, desde que o limite seguro seja respeitado |
| Por que não pula linha | `READPAST` ignora linhas bloqueadas por transações abertas, que serão pegas quando confirmarem; linhas só saem da fila de pendentes depois do confirm | Só lê linhas abaixo do menor `rowversion` ainda ativo, ou seja, linhas cujas transações já terminaram |

- **R16.** A escolha final entre as estratégias é decisão da PoC: **vira um ADR** com as observações coletadas (lag, duplicatas, tempo de recuperação, linhas de código, testes necessários). Até lá as duas coexistem e são selecionáveis por configuração.
- **R17.** A semântica exata de `min_active_rowversion()` na versão do SQL Server usada precisa ser confirmada e registrada (a versão da imagem fica fixada e documentada); a estratégia de ponto de leitura só é considerada correta depois dessa confirmação.

### Relay: instância única e instância ativa

- **R18.** Existe **no máximo um relay ativo por banco de origem**, controlado por **lease** guardado no próprio banco de origem. O lease expira, de modo que, se o relay ativo morrer, outro assume sem intervenção manual.
- **R19.** Se por falha dois relays rodarem ao mesmo tempo (por exemplo, lease expirado com a instância antiga ainda viva), **nenhuma mensagem pode ser perdida**; as duplicidades resultantes são absorvidas pelo consumidor. A segurança do sistema não depende de o lease ser perfeito: ela depende de at-least-once somado ao dedup no consumidor.
- **R19a.** O relay só roda quando o banco for o **primário/ativo**, e isso é **configurável**, para nunca publicar a partir de uma cópia (réplica) do banco.

### Dialeto e portabilidade

- **R20.** O acesso a banco é isolado por **dialeto**: SQL Server para a outbox, o lease e o relay (as duas estratégias dependem de `UPDLOCK`/`READPAST`/`ROWLOCK` e de `rowversion` com `min_active_rowversion()`, que o H2 não oferece). O relay não é executado sobre H2.
- **R21.** O módulo **não depende de framework de aplicação**; cada framework entra por um adaptador fino.
- **R22.** O módulo conhece o broker apenas por uma **interface de publicação**. Trocar de broker exige somente um novo adaptador. O adaptador RabbitMQ é o único que conhece detalhes do broker (filas quorum, publisher confirms).

### Retenção da outbox

- **R23.** A outbox tem **política de retenção/limpeza configurável**. A limpeza só remove linhas já publicadas com confirm e não pode afetar a ordem nem a segurança do relay: não remove linha ainda pendente, nem linha que o relay ainda precisa ler para calcular o limite seguro ou o ponto de leitura. Na estratégia de ponto de leitura (somente `INSERT`) a limpeza é em lote.

### Observabilidade

- **R24.** O módulo expõe, via Micrometer, em formato consumível pela operação, as métricas de publicação: **lag da outbox**, **mensagens pendentes** e **tentativas de retry** de publicação. Elas são observação para análise posterior; **nenhuma tem valor-meta**.
- **R25.** `correlationId` e `traceId` são **propagados de ponta a ponta**: persistidos na outbox (R5), publicados nos headers AMQP `correlationId` e `traceId` (e `messageId`, junto dos demais campos do envelope) e presentes nos logs do relay. Os logs são estruturados e trazem `messageId`, `correlationId` e `aggregateKey`. `correlationId` liga as mensagens do mesmo processo (e é o campo que o caso de coordenação de vários passos reutiliza).

### Requisitos não funcionais com efeito de negócio

- **R26.** O relay **não cresce em memória sem limite** (RSS estável sob carga): lê em lotes de tamanho limitado. Os limites de heap e de memória direta são explícitos.
- **R27.** Nenhum número de latência ou throughput é meta do domínio.

## Fluxos e ciclo de vida

### Fluxo feliz

1. A aplicação produtora abre a transação local, grava o dado de negócio e insere a linha na outbox (com `seq`, `message_id`, `aggregate_key`, `type`, `schema_ver`, `payload`, `created_at` e os metadados de propagação); faz commit.
2. O relay ativo (detentor do lease, em banco primário) lê as linhas pendentes de acordo com a estratégia configurada e as publica no broker na ordem por `aggregateKey`.
3. Com o confirm positivo, a linha é considerada enviada (marcada, removida ou ultrapassada pelo ponto de leitura, conforme a estratégia).
4. O relay registra as métricas de publicação.

### Estados de uma linha da outbox

`pendente` (gravada, ainda sem confirm) → `enviada` (confirm positivo recebido) → `removida` pela retenção. Uma linha pendente com falha de publicação continua `pendente` e é retentada com backoff. Como a forma de representar `enviada` depende da estratégia (marca, remoção ou posição abaixo do ponto de leitura), o estado é definido pela estratégia, não por uma coluna obrigatória comum.

### Comportamento esperado sob falhas

| Falha | Comportamento esperado |
|---|---|
| Falha na gravação do dado de negócio (antes do commit) | Nem o dado de negócio nem a linha da outbox existem |
| Relay cai depois de publicar e antes de registrar o envio | Ao reiniciar, republica a mensagem; a repetição é absorvida pelo dedup do consumidor |
| Broker indisponível e depois restaurado | As mensagens acumulam na outbox; após a volta, todas são publicadas, sem perda |
| Rede instável entre relay e broker | Retry com backoff, sem tempestade de tentativas |
| Dois relays ativos ao mesmo tempo | Nenhuma perda; duplicidades absorvidas pelo consumidor |
| Transações concorrentes confirmam fora da ordem de `seq` | Nenhuma mensagem é pulada, nas duas estratégias |
| Dois eventos do mesmo `aggregateKey` | Publicados na ordem em que ocorreram |

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

O metadado de propagação `traceId` (texto de até 64 caracteres) acompanha o envelope, viaja nos headers da mensagem e aparece nos logs.

### Mensagem publicada

Contrato completo em [`contracts/mensagem-publicada.md`](../../specs/001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md) da spec `001-mensageria-outbox-implementacao-modulo`.

- **Corpo:** os bytes do `payload`, com `content_type` `application/octet-stream`.
- **Headers AMQP:** `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` (ISO-8601 UTC com microssegundos) e `traceId`, todos obrigatórios. O consumidor lê o envelope dos headers.
- **Properties AMQP:** `message_id`, `correlation_id` e `type` espelham os headers para ferramentas de operação; `delivery_mode` é `2` (persistente).
- **Roteamento:** exchange `direct` durável, com a routing key igual ao `type` do envelope. A publicação usa `mandatory=true`: uma mensagem devolvida por `basic.return` (nenhuma fila ligada à routing key) é falha de publicação, e a linha permanece pendente, porque o confirm positivo do broker também chega para mensagem sem fila e descartada.

### Tabela `outbox` (banco de origem, SQL Server)

| Coluna | Tipo | Observação |
|---|---|---|
| `seq` | `BIGINT IDENTITY(1,1)` PK | Atribuído antes do commit; não serve sozinho como limite de leitura |
| `message_id` | `UNIQUEIDENTIFIER` NOT NULL | Vira o `messageId` |
| `aggregate_key` | `NVARCHAR(100)` NOT NULL | Chave de ordenação |
| `type` | `NVARCHAR(100)` NOT NULL | Tipo do evento |
| `schema_ver` | `SMALLINT` NOT NULL | Versão do contrato do payload |
| `payload` | `VARBINARY(MAX)` NOT NULL | Payload mínimo |
| `created_at` | `DATETIME2` NOT NULL, padrão `SYSUTCDATETIME()` | Momento da gravação |

Os esquemas de dados são esboços de PoC: nomes, tipos e índices podem ser ajustados com o DBA. Para cumprir as regras acima, a estrutura final também precisa: (a) persistir `correlationId`, `idempotencyKey`, `occurredAt` e `traceId` (colunas próprias ou envelope serializado no payload); (b) oferecer, para a estratégia de ponto de leitura, uma coluna `rowversion`; (c) oferecer, para a estratégia claim, a forma de marcar a linha como enviada quando a estratégia marca em vez de remover.

### Estado do relay

- **Lease:** registro no banco de origem que identifica o relay ativo e tem expiração; o relay ativo o renova e outro só o assume depois de expirado. A estrutura (colunas, duração, intervalo de renovação) é definida na implementação.
- **Ponto de leitura:** posição persistida pela estratégia 2, usada para retomar a leitura sem reprocessar o que já passou do limite seguro.

### Broker

A fila de destino é `quorum`, durável, declarada pela aplicação, com publisher confirms. A exchange é `direct` e durável (nome configurável, padrão `mensageria.outbox`), e o roteamento usa o `type` como routing key. A fila é declarada com `x-queue-type=quorum`, `durable=true`, `exclusive=false`, `auto-delete=false` e nenhum outro argumento; todo declarante da fila repete exatamente esses argumentos, ou recebe `PRECONDITION_FAILED`.

## Restrições e validações

- Sem XA, sem JTA, sem gerenciador de transação externo; um banco/schema separado por aplicação e nenhuma transação local entre produtor e consumidor.
- Publisher confirms e filas quorum são obrigatórios.
- O módulo não valida o conteúdo do payload.
- Ordem apenas por `aggregateKey`; nenhuma garantia de ordem global.
- Credenciais de banco e broker nunca ficam versionadas.

## Parâmetros configuráveis

Os parâmetros abaixo são configuráveis; nenhum valor padrão é definido pelas fontes e os valores são escolhidos e registrados na implementação:

- relay habilitado somente quando o banco for o primário/ativo (liga/desliga o relay por instância);
- estratégia de relay em uso (claim ou ponto de leitura);
- política de backoff da publicação;
- retenção/limpeza da outbox;
- duração e renovação do lease;
- tamanho de lote do relay;
- limites de heap e de memória direta do processo do relay.

O repositório não declara variáveis de ambiente específicas deste domínio.

## Integrações e dependências externas

- **SQL Server** (executado em Docker): banco da outbox, do lease e do relay; as duas estratégias dependem de `UPDLOCK`/`READPAST`/`ROWLOCK` e de `rowversion`/`min_active_rowversion()`.
- **RabbitMQ:** broker com filas quorum e publisher confirms, acessado pelo adaptador de broker.
- **Micrometer:** métricas.

As dependências técnicas e as precondições de ambiente do domínio estão em [references/technical-dependencies.md](references/technical-dependencies.md).

## Pontos em aberto

- Se o código da PoC é descartável ou evolui para o starter interno é uma pergunta sem decisão; não bloqueia o domínio, e o isolamento do módulo (sem framework de aplicação, broker por interface) o mantém reaproveitável.
- A confirmação da semântica de `min_active_rowversion()` (R17) deve ser verificada na implementação. A compatibilidade de Micrometer e Testcontainers com a plataforma do módulo (Java 8 como piso) é verificada pela suíte do módulo nos JDKs 8, 11, 17, 21 e 25.

## Manutenção

- Mudança **deliberada** de comportamento, com a intenção conhecida: atualize esta skill.
- Divergência **semântica** entre esta skill e o código sem decisão registrada que a resolva: trate como lacuna e escale para decisão humana; não ajuste a skill nem o código por conta própria.
- Refactor que preserva a regra: não altere a skill.
