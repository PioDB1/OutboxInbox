# Casos de teste: Mensageria Outbox — implementação do módulo produtor

Catálogo derivado de [`spec.md`](./spec.md) e [`plan.md`](./plan.md). O módulo é uma biblioteca sem endpoint; os casos exercitam a API de [`contracts/api-modulo.md`](./contracts/api-modulo.md), observam o banco ([`data-model.md`](./data-model.md)) e a fila, e seguem a convenção de testes do plano (`*Test` sem Docker; `*IT` com Testcontainers). Cada caso é o ponto de partida de um teste automatizado e também um roteiro de validação manual.

## Pré-condições

- Docker em execução (exceto nos casos marcados como "sem Docker"). Contêineres de `compose.yaml` ou de Testcontainers com as imagens `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`, `rabbitmq:4.3.6-management` e `ghcr.io/shopify/toxiproxy:2.10.0`; o Toxiproxy fica entre o relay e o RabbitMQ.
- Um banco de origem com o esquema instalado por `OutboxSchemaInstaller.install(dataSource)` e uma tabela de negócio de teste no mesmo banco.
- Exchange `mensageria.outbox` declarada com `RabbitTopology.declareExchange` e uma fila `quorum` declarada com `RabbitTopology.declareQuorumQueue(queue, exchange, routingKey)` para a routing key do `type` usado nos casos; um leitor de teste consome essa fila para inspecionar mensagens.
- Um `MeterRegistry` Micrometer em memória e `RelayConfig` criada por `RelayConfig.fromProperties` com as chaves `outbox.relay.*` do plano; os casos citam apenas as chaves que alteram dos padrões.
- Credenciais dos contêineres efêmeras, fornecidas por variáveis de ambiente ou propriedades externas; nada versionado.
- **Variantes de estratégia:** os casos marcados "(claim e ponto de leitura)" rodam duas vezes, com `outbox.relay.strategy=claim` e `outbox.relay.strategy=read-point`, e com `outbox.relay.enabled=true`.
- **Linha enviada:** na estratégia claim, `sent_at` preenchido; na de ponto de leitura, `rv <= last_rv` em `outbox_read_point`. **Linha pendente:** `sent_at IS NULL` (claim) ou `rv > last_rv` (ponto de leitura).
- Cada teste começa com `outbox`, `outbox_lease`, `outbox_read_point` e a fila vazias e proxies do Toxiproxy sem toxics.

## História 1 — Inserção transacional na outbox

### TC-1 (obrigatório) — Inserção confirmada grava a linha completa

1. Abrir uma conexão com `autoCommit=false`, inserir um registro na tabela de negócio de teste.
2. Chamar `OutboxWriter.insert(connection, envelope)` com um `Envelope` com todos os campos preenchidos (`correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt`, `payload`, `traceId`).
3. Fazer commit e ler `outbox` diretamente no banco.

**Esperado:** existe exatamente uma linha em `outbox` com `message_id`, `correlation_id`, `idempotency_key`, `aggregate_key`, `type`, `schema_ver`, `occurred_at`, `trace_id`, `payload`, `seq` e `created_at` preenchidos e iguais aos do envelope; o valor devolvido por `insert` é o `seq`; o registro de negócio existe.

### TC-2 (obrigatório) — `seq` cresce entre inserções consecutivas

1. Inserir dois envelopes, um após o outro, em transações confirmadas.

**Esperado:** o `seq` da segunda linha é maior que o da primeira.

### TC-3 (obrigatório) — Rollback não deixa linha nem dado de negócio

1. Abrir uma conexão com `autoCommit=false`, inserir um registro na tabela de negócio de teste e chamar `OutboxWriter.insert`.
2. Fazer rollback e ler as duas tabelas.

**Esperado:** não existe registro de negócio nem linha em `outbox`.

### TC-4 (obrigatório) — `occurredAt` mantém o instante informado

1. Inserir um envelope com `occurredAt` anterior em horas ao momento atual e confirmar a transação.
2. Rodar o relay até a publicação e ler a linha e o header `occurredAt` da mensagem.

**Esperado:** `occurred_at` e o header `occurredAt` (ISO-8601 UTC) correspondem ao instante informado e diferem de `created_at`.

### TC-5 (obrigatório) — Payload binário qualquer é preservado

1. Inserir um envelope cujo `payload` contém bytes que não formam texto UTF-8 nem JSON (por exemplo, `0x00 0xFF 0xFE 0x80`).
2. Confirmar a transação, rodar o relay até a publicação e ler o corpo da mensagem na fila.

**Esperado:** o corpo da mensagem é idêntico, byte a byte, ao payload inserido.

### TC-6 (obrigatório) — Inserção com conexão em autocommit é rejeitada

1. Abrir uma conexão com `autoCommit=true`.
2. Chamar `OutboxWriter.insert(connection, envelope)`.

**Esperado:** a chamada lança `IllegalStateException`; nenhuma linha é gravada em `outbox`.

### TC-7 (recomendado) — Envelope inválido é rejeitado na construção

1. Construir `Envelope` com um campo obrigatório nulo; repetir com um campo de texto vazio; repetir com `aggregateKey` de 101 caracteres, `idempotencyKey` de 201, `type` de 101 e `traceId` de 65. Sem Docker.

**Esperado:** cada construção lança `IllegalArgumentException`.

### TC-8 (recomendado) — `messageId` duplicado é recusado pelo banco

1. Inserir um envelope e confirmar.
2. Inserir outro envelope com o mesmo `messageId`.

**Esperado:** a segunda inserção falha pela restrição `UNIQUE (message_id)`; permanece uma única linha.

### TC-9 (recomendado) — Instalação do esquema é idempotente

1. Executar `OutboxSchemaInstaller.install(dataSource)` duas vezes sobre o mesmo banco.
2. Inserir um envelope e confirmar.

**Esperado:** as duas execuções terminam sem erro; as tabelas `outbox`, `outbox_lease` e `outbox_read_point` existem com os índices de `data-model.md`, e a inserção funciona.

## História 2 — Publicação com confirm do broker

### TC-10 (obrigatório) — Publicação com headers, properties e corpo (claim e ponto de leitura)

1. Inserir um envelope e confirmar a transação.
2. Executar `relay.runOnce()` e ler a mensagem na fila.

**Esperado:** a mensagem chega à fila `quorum` durável com corpo igual ao payload, headers `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` e `traceId` iguais aos do envelope, properties `message_id`, `correlation_id`, `type`, `delivery_mode=2` e `content_type=application/octet-stream`; a linha passa a enviada somente depois do confirm.

### TC-11 (obrigatório) — Confirm negativo, ausência de confirm ou erro de publicação mantém a linha pendente (claim e ponto de leitura)

1. Inserir um envelope e confirmar.
2. Criar o relay com um `MessagePublisher` de teste que lança `PublishException`; executar `relay.runOnce()`.

**Esperado:** a linha continua pendente e nenhuma mensagem chega à fila.

### TC-12 (obrigatório) — Mensagem devolvida por falta de fila ligada não é dada como enviada (claim e ponto de leitura)

1. Com a exchange declarada e nenhuma fila ligada à routing key do `type`, inserir um envelope e confirmar.
2. Executar `relay.runOnce()` e verificar a linha.
3. Declarar a fila com `RabbitTopology.declareQuorumQueue`, esperar o fim do backoff e executar `relay.runOnce()` de novo.

**Esperado:** após o passo 2 a linha continua pendente; após o passo 3 a mensagem está na fila e a linha está enviada.

### TC-13 (obrigatório) — Queda depois do confirm e antes do registro republica a mensagem (claim e ponto de leitura)

1. Inserir um envelope e confirmar.
2. Executar um ciclo com um decorator de teste que lança uma exceção depois do confirm positivo e antes de registrar o envio; descartar essa instância do relay.
3. Criar um novo relay sobre o mesmo banco e executar `relay.runOnce()`.

**Esperado:** a fila contém duas mensagens com o mesmo `messageId`; a linha termina enviada.

### TC-14 (obrigatório) — Fronteira de pacotes (sem Docker)

1. Percorrer os fontes de `src/main/java` do módulo e listar os `import`.

**Esperado:** nenhum fonte importa `org.springframework`; somente fontes do pacote `rabbit` importam `com.rabbitmq`.

### TC-15 (recomendado) — Outra implementação de `MessagePublisher` substitui o broker

1. Criar o relay com um `MessagePublisher` de teste que grava as mensagens em memória.
2. Inserir um envelope, confirmar e executar `relay.runOnce()`.

**Esperado:** o `MessagePublisher` de teste recebe a mensagem e a linha é enviada, sem uso do RabbitMQ e sem alteração do núcleo.

### TC-16 (recomendado) — Mapeamento do envelope para headers e properties (sem Docker)

1. Chamar `RabbitMessagePublisher.publish` com um `Channel` simulado por `java.lang.reflect.Proxy`, sem broker.
2. Inspecionar os argumentos recebidos por `basicPublish`.

**Esperado:** exchange configurada, routing key igual ao `type`, `mandatory=true`, headers e properties conforme [`contracts/mensagem-publicada.md`](./contracts/mensagem-publicada.md).

### TC-17 (obrigatório) — Erro de conexão com o broker mantém a linha pendente (claim e ponto de leitura)

1. Inserir um envelope e confirmar.
2. Desabilitar o proxy do Toxiproxy para o RabbitMQ e executar `relay.runOnce()`.

**Esperado:** a linha continua pendente e nenhuma linha é descartada.

## História 3 — Ordem por `aggregateKey` e nenhuma linha pulada

### TC-18 (obrigatório) — Ordem do mesmo `aggregateKey` (claim e ponto de leitura)

1. Inserir, em transações confirmadas em sequência, dois envelopes com o mesmo `aggregateKey`.
2. Executar `relay.runOnce()` até as duas linhas serem enviadas e ler as mensagens na fila.

**Esperado:** a mensagem mais antiga é publicada antes da mais nova.

### TC-19 (obrigatório) — Falha de um `aggregateKey` não libera a mensagem posterior da mesma chave (claim e ponto de leitura)

1. Inserir dois envelopes do `aggregateKey` X e um do `aggregateKey` Y, nessa ordem de `seq` (X1, Y1, X2).
2. Executar um ciclo com um `MessagePublisher` de teste que falha somente para X1.
3. Ler a fila e as linhas.

**Esperado:** Y1 é publicada; X2 não é publicada antes de X1; X1 e X2 continuam pendentes; as linhas de X seguem a ordem depois que X1 é publicada.

### TC-20 (obrigatório) — Commit fora da ordem de `seq` em `aggregateKey` diferentes (claim e ponto de leitura)

1. Abrir a transação T1 e inserir um envelope (obtém `seq` menor); manter T1 aberta.
2. Abrir a transação T2, inserir um envelope de outro `aggregateKey` (obtém `seq` maior) e confirmar.
3. Executar alguns ciclos do relay; só então confirmar T1; executar ciclos até estabilizar.

**Esperado:** as duas mensagens são publicadas; a mensagem de T1 não é pulada.

### TC-21 (obrigatório) — Commit fora da ordem de `seq` no mesmo `aggregateKey` (claim e ponto de leitura)

1. Abrir T1 e inserir um envelope do `aggregateKey` X (`seq` menor); manter T1 aberta.
2. Abrir T2, inserir outro envelope do `aggregateKey` X (`seq` maior) e confirmar.
3. Executar ciclos do relay e ler a fila; confirmar T1; executar ciclos até estabilizar.

**Esperado:** nenhuma mensagem de X é publicada enquanto T1 está aberta; depois do commit de T1, a mensagem de T1 é publicada antes da de T2.

### TC-22 (recomendado) — Transação revertida deixa de bloquear o `aggregateKey` (claim)

1. Abrir T1 e inserir um envelope do `aggregateKey` X; abrir T2, inserir outro de X e confirmar.
2. Executar um ciclo (nada é publicado de X); fazer rollback de T1; executar outro ciclo.

**Esperado:** a mensagem de T2 é publicada no ciclo seguinte ao rollback.

### TC-23 (obrigatório) — Seleção da estratégia por configuração

1. Criar o relay com `outbox.relay.strategy=claim`; inserir um envelope e executar `relay.runOnce()`.
2. Repetir com `outbox.relay.strategy=read-point`.

**Esperado:** com `claim`, a instância é `ClaimRelay`, `sent_at` é preenchido e `outbox_read_point` permanece em `0x0000000000000000`; com `read-point`, a instância é `ReadPointRelay`, `sent_at` permanece nulo e `last_rv` avança.

### TC-24 (obrigatório) — Estratégia ausente ou desconhecida impede a inicialização (sem Docker)

1. Chamar `RelayConfig.fromProperties` sem a chave `outbox.relay.strategy`.
2. Chamar com `outbox.relay.strategy=outra`.

**Esperado:** as duas chamadas lançam `IllegalArgumentException` com mensagem que cita os valores aceitos (`claim` e `read-point`).

### TC-25 (obrigatório) — Semântica de `min_active_rowversion()` na imagem fixada

1. Ler `@@DBTS` e `MIN_ACTIVE_ROWVERSION()` sem transações abertas.
2. Abrir uma transação que insere uma linha em `outbox` (sem confirmar) e ler `MIN_ACTIVE_ROWVERSION()` em outra conexão.
3. Confirmar a transação e ler `MIN_ACTIVE_ROWVERSION()` de novo.

**Esperado:** sem transações abertas o valor é `@@DBTS + 1`; com a transação aberta o valor é menor ou igual ao `rv` da linha inserida; após o commit o valor avança acima desse `rv`. O resultado observado é registrado no ADR `0001`.

### TC-26 (recomendado) — Ponto de leitura avança até o prefixo contíguo publicado

1. Com a estratégia de ponto de leitura, inserir R1, R2 e R3 em transações confirmadas, com a publicação de R2 forçada a falhar por um `MessagePublisher` de teste.
2. Executar um ciclo e ler `outbox_read_point`.

**Esperado:** R1 e R3 foram publicadas (R3 de `aggregateKey` diferente de R2); `last_rv` é o `rv` de R1; R2 continua pendente.

### TC-27 (recomendado) — Retomada do ponto de leitura após reinício

1. Com a estratégia de ponto de leitura, publicar duas linhas e descartar o relay.
2. Inserir uma terceira linha, criar um novo relay sobre o mesmo banco e executar `relay.runOnce()`.

**Esperado:** a terceira linha é publicada; `last_rv` vem do banco, e a leitura recomeça a partir dele.

### TC-28 (recomendado) — A estratégia de ponto de leitura somente insere na outbox

1. Com a estratégia de ponto de leitura, publicar uma linha e ler `outbox` e `outbox_read_point`.

**Esperado:** a linha publicada mantém `sent_at` nulo e fica com `rv <= last_rv`; `PendingQuery` da estratégia de ponto de leitura devolve contagem zero.

## História 4 — Retry com backoff e acúmulo enquanto o broker está fora

### TC-29 (obrigatório) — Broker indisponível e depois restaurado (claim e ponto de leitura)

1. Desabilitar o proxy do Toxiproxy para o RabbitMQ.
2. Inserir N envelopes (por exemplo, 50) em transações confirmadas e executar ciclos do relay.
3. Reabilitar o proxy, esperar o fim do backoff e executar ciclos até estabilizar.

**Esperado:** durante a indisponibilidade as N linhas permanecem em `outbox`; depois da volta, as N mensagens estão na fila e as N linhas estão enviadas; nenhuma linha foi descartada.

### TC-30 (obrigatório) — Intervalos entre tentativas crescem até o teto (claim e ponto de leitura)

1. Com `outbox.relay.backoff.initial=200ms`, `backoff.multiplier=2.0` e `backoff.max=2s`, manter o broker indisponível e inserir um envelope.
2. Executar o relay com `start()` por tempo suficiente para várias tentativas e registrar o instante de cada tentativa de publicação da linha.

**Esperado:** os intervalos entre tentativas são não decrescentes e nunca excedem 2 s.

### TC-31 (obrigatório) — Backoff evita tentativas em laço apertado (claim e ponto de leitura)

1. Repetir o cenário de TC-30 por uma janela fixa de 10 s.

**Esperado:** o número de tentativas na janela é menor que o número de ciclos que ocorreriam sem backoff com `poll-interval` de 500 ms.

### TC-32 (obrigatório) — Linha pendente é publicada logo que o backoff vigente termina (claim e ponto de leitura)

1. Com o broker indisponível, inserir um envelope e deixar o relay tentar.
2. Reabilitar o proxy e medir o tempo até a mensagem aparecer na fila.

**Esperado:** a mensagem aparece na fila em no máximo o intervalo de backoff vigente mais o tempo de um ciclo.

### TC-33 (recomendado) — Backoff é zerado depois de um sucesso (claim e ponto de leitura)

1. Fazer uma linha falhar duas vezes e depois ser publicada com sucesso.
2. Inserir uma nova linha do mesmo `aggregateKey` e fazer a primeira tentativa falhar.

**Esperado:** o intervalo antes da nova tentativa da segunda linha é o inicial (200 ms).

### TC-34 (obrigatório) — Rede instável entre relay e broker (claim e ponto de leitura)

1. Aplicar ao proxy do Toxiproxy, sucessivamente, os toxics `latency`, `timeout` e `reset_peer`.
2. Inserir envelopes e executar o relay com `start()` durante cada toxic; remover o toxic e esperar a estabilização.

**Esperado:** durante os toxics, as tentativas seguem o backoff; depois de removidos, todas as mensagens são publicadas e nenhuma linha é descartada.

### TC-35 (recomendado) — Contador de retry aumenta a cada nova tentativa

1. Fazer uma linha falhar três vezes e depois ser publicada.

**Esperado:** `outbox.publish.retries` cresce a cada tentativa posterior à primeira falha da linha e não cresce na primeira tentativa.

## História 5 — Lease, instância única e primário/ativo

### TC-36 (obrigatório) — Somente o detentor do lease publica (claim e ponto de leitura)

1. Criar dois relays com `holderId` distintos sobre o mesmo banco e iniciar os dois.
2. Inserir envelopes e esperar a publicação; ler `outbox_lease`.

**Esperado:** `outbox_lease` tem uma única linha com um dos dois `holder_id`; somente a instância detentora publica.

### TC-37 (obrigatório) — Outro relay assume depois da expiração (claim e ponto de leitura)

1. Com `lease.duration` curto, iniciar o relay A; parar a thread de A por um decorator de teste, sem chamar `close()` e sem liberar o lease.
2. Iniciar o relay B, inserir envelopes e esperar a expiração.

**Esperado:** B adquire o lease depois da expiração e publica as linhas pendentes sem intervenção manual.

### TC-38 (obrigatório) — Dois relays ativos por sobreposição forçada (claim e ponto de leitura)

1. Com `lease.duration` curto, iniciar A e congelar A por um decorator de teste com latch, depois de ler um lote e antes de publicar.
2. Deixar o lease de A expirar e B assumir e publicar; descongelar A.

**Esperado:** nenhuma mensagem é perdida (todas as linhas são enviadas); as mensagens publicadas duas vezes têm o mesmo `messageId`.

### TC-39 (obrigatório) — Relay desabilitado não adquire lease nem publica

1. Criar o relay com `outbox.relay.enabled=false`, inserir um envelope e chamar `start()`.

**Esperado:** `outbox_lease` permanece vazia, a linha continua pendente e nenhuma mensagem chega à fila.

### TC-40 (recomendado) — O padrão de `outbox.relay.enabled` é `false` (sem Docker)

1. Chamar `RelayConfig.fromProperties` com `outbox.relay.strategy=claim` e sem `outbox.relay.enabled`.

**Esperado:** `enabled` é `false` e os demais parâmetros têm os padrões do plano (`batch-size=100`, `poll-interval=500ms`, `confirm-timeout=5s`, `backoff.initial=200ms`, `backoff.multiplier=2.0`, `backoff.max=30s`, `lease.duration=15s`, `lease.renew-interval=5s`, `retention.max-age=7d`, `retention.interval=60s`, `retention.batch-size=1000`, `exchange=mensageria.outbox`).

### TC-41 (obrigatório) — Reinício a qualquer momento sem estado em memória (claim e ponto de leitura)

1. Inserir mais linhas que o `batch-size` (por exemplo, 250 com `batch-size=100`).
2. Iniciar o relay, interrompê-lo no meio de um lote (`close()` e descarte da instância) e criar um novo relay sobre o mesmo banco.

**Esperado:** o novo relay conclui a publicação de todas as linhas sem intervenção manual; nenhuma linha é perdida; eventuais mensagens repetidas têm o mesmo `messageId`.

### TC-42 (recomendado) — `close()` libera o lease

1. Iniciar o relay A, esperar a aquisição do lease e chamar `close()`.
2. Iniciar o relay B.

**Esperado:** B adquire o lease antes de o `lease.duration` de A expirar.

### TC-43 (recomendado) — O detentor renova o lease

1. Iniciar um relay com `lease.duration=15s` e `lease.renew-interval=5s`; ler `expires_at` em dois instantes separados por mais de 5 s.

**Esperado:** `expires_at` avança e o `holder_id` não muda.

## História 6 — Retenção da outbox

### TC-44 (obrigatório) — Limpeza remove somente linhas enviadas além da idade máxima (claim e ponto de leitura)

1. Com `retention.max-age` curto (por exemplo, 2 s) e `retention.interval` curto, publicar três linhas e esperar mais que `max-age`.
2. Inserir uma quarta linha e confirmar; esperar a rotina de limpeza executar.

**Esperado:** as três linhas enviadas antigas foram removidas; a linha recente continua em `outbox`.

### TC-45 (obrigatório) — Limpeza nunca remove linha pendente (claim e ponto de leitura)

1. Com `retention.max-age` curto e o broker indisponível, inserir uma linha e esperar muito mais que `max-age`.
2. Aguardar a rotina de limpeza executar.

**Esperado:** a linha pendente continua em `outbox`.

### TC-46 (obrigatório) — Limpeza do ponto de leitura preserva as linhas acima do ponto e o relay não pula mensagens

1. Com a estratégia de ponto de leitura, publicar linhas e esperar a limpeza removê-las.
2. Inserir novas linhas, uma delas com transação aberta (T1) enquanto outra posterior confirma; executar ciclos; confirmar T1.

**Esperado:** a limpeza removeu apenas linhas com `rv <= last_rv`; todas as novas linhas são publicadas, inclusive a de T1.

### TC-47 (recomendado) — Limpeza em lotes de `retention.batch-size`

1. Com `retention.batch-size=10` e 35 linhas enviadas e vencidas, executar a rotina de limpeza.

**Esperado:** a remoção ocorre em lotes de no máximo 10 linhas e, ao final, as 35 linhas estão removidas.

### TC-48 (recomendado) — Somente o relay ativo executa a limpeza

1. Com dois relays sobre o mesmo banco, linhas enviadas e vencidas e `retention.interval` curto, observar quem executa a limpeza.

**Esperado:** a limpeza é executada pelo relay detentor do lease.

## História 7 — Métricas e logs estruturados

### TC-49 (obrigatório) — Métricas registradas

1. Criar o relay com um `MeterRegistry` e executar um ciclo sem linhas pendentes.

**Esperado:** estão registrados `outbox.lag.seconds` (valor 0), `outbox.pending` (valor 0) e `outbox.publish.retries` (valor 0).

### TC-50 (obrigatório) — Lag e pendentes crescem com o broker fora e voltam a zero

1. Desabilitar o proxy do RabbitMQ, inserir envelopes e executar ciclos; ler `outbox.pending` e `outbox.lag.seconds`.
2. Reabilitar o proxy, esperar a publicação de todas e ler as métricas.

**Esperado:** durante a indisponibilidade `outbox.pending` é igual ao número de linhas inseridas e `outbox.lag.seconds` cresce; depois da publicação ambas voltam a 0.

### TC-51 (recomendado) — `PendingQuery` devolve contagem e idade (claim e ponto de leitura)

1. Inserir duas linhas em momentos distintos com o broker indisponível e chamar `PendingQuery.pending(dataSource, strategy)`.

**Esperado:** `count` é 2 e `oldestAge` é a idade da linha mais antiga.

### TC-52 (obrigatório) — `correlationId` e `traceId` nos headers e nos logs do relay

1. Com o Logback de teste e um `ListAppender`, inserir um envelope com `correlationId` e `traceId` conhecidos e publicá-lo.
2. Ler os headers da mensagem e os eventos de log do relay sobre a linha.

**Esperado:** a mensagem traz os headers `correlationId` e `traceId` com esses valores; todo log do relay sobre a linha tem no MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`.

### TC-53 (recomendado) — O MDC é limpo ao fim da tentativa

1. Publicar uma linha e, depois do ciclo, ler o MDC da thread do relay.

**Esperado:** as chaves `messageId`, `correlationId`, `aggregateKey` e `traceId` não estão presentes.

## História 8 — ADR da estratégia de relay

### TC-54 (obrigatório) — ADR `0001` e índice existem no formato da skill (sem Docker)

1. Abrir `docs/adr/README.md` e `docs/adr/0001-estrategia-de-relay.md`.

**Esperado:** o README tem uma linha para o ADR com número, título, status e data; o ADR segue o formato da skill `registro-de-adrs` com status `Proposto`.

### TC-55 (obrigatório) — Observações da PoC preenchidas com dados medidos (sem Docker)

1. Abrir a seção "Observações da PoC" do ADR `0001`.

**Esperado:** a seção traz, para cada estratégia, o resultado do cenário de commits fora da ordem de `seq`, lag, pendentes, tentativas de retry, tempo de recuperação, contenção observada, linhas de código e quantidade de testes, e a semântica de `min_active_rowversion()` observada na imagem `2019-CU32-ubuntu-20.04` (TC-25).

## História 9 — Ambiente e build reproduzíveis

### TC-56 (obrigatório) — Compose sobe os três serviços saudáveis

1. Criar `.env` a partir de `.env.example`.
2. Executar `docker compose up -d --wait` e depois `docker compose ps`.

**Esperado:** `sqlserver`, `rabbitmq` e `toxiproxy` ficam saudáveis; as imagens usam as tags fixas do plano (nenhuma `latest`).

### TC-57 (obrigatório) — Build completo em Java 21

1. Na raiz, com Docker em execução, executar `mvn clean verify`.

**Esperado:** o módulo `mensageria-outbox` compila em Java 21 e os testes `*Test` e `*IT` passam, com contêineres próprios e credenciais efêmeras.

### TC-58 (recomendado) — Enforcer rejeita JDK anterior ao 21

1. Executar `mvn validate` com um JDK 17.

**Esperado:** o build falha pela regra do `maven-enforcer-plugin` que exige Java 21.

### TC-59 (obrigatório) — Nenhum segredo versionado e `.gitignore` correto (sem Docker)

1. Listar os arquivos versionados e buscar por senhas, chaves e strings de conexão com segredo; abrir `.gitignore`.

**Esperado:** nenhuma credencial está versionada; `.env` e `target/` constam do `.gitignore`; `.env.example` contém apenas nomes e valores fictícios.

### TC-60 (recomendado) — Tags do compose coincidem com as dos testes (sem Docker)

1. Executar o teste unitário que compara as tags de `compose.yaml` com a classe `Images`.

**Esperado:** as tags são idênticas.

### TC-61 (obrigatório) — Carga com memória limitada

1. Executar o teste de carga do failsafe (`-Xmx256m -XX:MaxDirectMemorySize=64m`) com 10 000 linhas pendentes e `batch-size=100`, nas duas estratégias.
2. Medir o heap usado após `System.gc()` em três pontos do processamento.

**Esperado:** o processo termina sem `OutOfMemoryError`, todas as linhas são publicadas e o heap não cresce mais de 32 MiB entre o segundo e o terceiro ponto.

### TC-62 (obrigatório) — Credenciais chegam por variáveis de ambiente ou propriedades externas

1. Subir o ambiente de teste com credenciais fornecidas por variável de ambiente ou propriedade externa e criar o `DataSource` e o `ConnectionFactory` a partir delas.
2. Buscar nos fontes do módulo valores de senha ou usuário.

**Esperado:** o relay conecta ao SQL Server e ao RabbitMQ com essas credenciais; o módulo não contém valor fixo de credencial.
