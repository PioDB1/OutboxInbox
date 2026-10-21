# Casos de teste: Mensageria Outbox — núcleo da inbox no `mensageria-outbox-core`

Catálogo derivado de [`spec.md`](./spec.md), [`plan.md`](./plan.md), [`research.md`](./research.md), [`data-model.md`](./data-model.md) e [`contracts/api-inbox.md`](./contracts/api-inbox.md). O consumidor é uma biblioteca sem endpoint; os casos observam a API Java, o banco (tabela `inbox` e tabela de negócio de teste), as filas do RabbitMQ (principal e DLQ), as métricas e os logs. Cada caso é o ponto de partida de um teste `*Test` ou `*IT` e também um roteiro de validação manual.

## Pré-condições

- Repositório na raiz, com o `pom.xml` raiz; o build roda em JDK 21 com Maven 3.9 ou superior. A suíte do `core` roda nos JDKs 8, 11, 17, 21 e 25 por `mvn -pl mensageria-outbox/core verify -Djvm=<JDK_HOME>/bin/java`.
- Casos "sem Docker": `*Test` (surefire), com H2 em memória e uma `MessageSource` simulada. Casos "com Docker": `*IT` (failsafe, Testcontainers) com RabbitMQ, Toxiproxy, H2 em arquivo e SQL Server, com credenciais efêmeras ou as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER` e `RABBITMQ_DEFAULT_PASS`; sem Docker em execução eles são ignorados e não contam como verificados.
- Nos casos com broker: exchange `direct` declarado com `RabbitTopology.declareExchange`, fila principal quorum declarada com `RabbitTopology.declareQuorumQueue(channel, queue, exchange, routingKey)` e DLQ declarada com `RabbitTopology.declareQuorumQueue(channel, dlq)`; mensagens publicadas pelo `RabbitMessagePublisher`.
- Banco de teste com a tabela `inbox` criada por `InboxSchemaInstaller` e uma tabela de negócio de teste `efeito (id UUID/UNIQUEIDENTIFIER, message_id, ...)` gravada pelo tratador de teste na conexão recebida.
- Configuração de referência salvo indicação: `max-attempts` 5, `backoff.initial` 200 ms, `multiplier` 2,0, `backoff.max` 30 s, `max-in-flight` 50, `workers` 8, `confirm-timeout` 5 s. Nos casos de tempo, o backoff é reduzido (por exemplo, `initial` 20 ms) para a suíte não esperar segundos.
- Os casos de Spring usam o `ApplicationContextRunner` do código comum, com `DataSource` H2 em memória, `DataSourceTransactionManager` e uma `MessageSource` simulada, e rodam nos dois adaptadores.

## História 1 — Leitura do envelope (R1 a R3)

### TC-1 (obrigatório) — Envelope lido dos headers e payload intacto

1. Publicar pelo `RabbitMessagePublisher` uma mensagem com payload binário que contém todos os 256 valores de byte.
2. Iniciar o `InboxConsumer` na fila de destino, com um tratador de teste que guarda o `Envelope` recebido.
3. Comparar o envelope recebido com o publicado.

**Esperado:** `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` (com microssegundos) e `traceId` iguais aos publicados; `payload` igual byte a byte; o tratador é chamado uma vez. Sem Docker, o mesmo caso roda com um `Delivery` simulado de headers e corpo equivalentes.

### TC-2 (obrigatório) — Headers prevalecem sobre as properties AMQP

1. Montar uma entrega em que as properties `message_id`, `correlation_id` e `type` são diferentes dos headers de mesmo nome.
2. Processar a entrega pelo `EnvelopeReader`.

**Esperado:** o `Envelope` traz os valores dos headers.

### TC-3 (obrigatório) — Header ausente ou fora do formato é falha de processamento

1. Para cada variação — sem `messageId`, sem `aggregateKey`, `messageId` que não é UUID, `correlationId` que não é UUID, `occurredAt` fora de ISO-8601 UTC, `schemaVersion` não numérico, `type` com 101 caracteres — montar uma entrega com os demais headers válidos.
2. Processar a entrega com `max-attempts` 2 e backoff curto.

**Esperado:** o tratador nunca é chamado; a tabela `inbox` fica vazia; a mensagem tem 2 tentativas, o contador `inbox.process.retries` vale 1, a mensagem é publicada na DLQ com `x-inbox-failure` = `max-attempts` e a original recebe ack.

### TC-4 (recomendado) — Headers de texto chegam do broker como `String`

1. Publicar uma mensagem pelo `RabbitMessagePublisher` e consumi-la pelo `RabbitMessageSource`.
2. Inspecionar o `Delivery.headers()` recebido por `onDelivery`.

**Esperado:** os headers `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `occurredAt` e `traceId` são `String` (não `LongString`) e `schemaVersion` é `Number`.

## História 2 — Transação local e ack depois do commit (R4)

### TC-5 (obrigatório) — Efeito, inbox e ack, nessa ordem de efeito observável

1. Iniciar o consumidor com um tratador que grava uma linha em `efeito` pela conexão recebida e registra o instante do commit visível por uma segunda conexão.
2. Publicar uma mensagem nova.
3. Aguardar o ack (fila principal sem mensagens e sem mensagens sem ack).

**Esperado:** a tabela `inbox` tem uma linha com o `message_id` e `consumed_at` preenchido; `efeito` tem uma linha; o ack só ocorre depois de as duas linhas serem visíveis para uma segunda conexão (o simulador de `MessageSource` registra a ordem `commit` antes de `ack`).

### TC-6 (obrigatório) — Falha do tratador desfaz tudo e não confirma

1. Iniciar o consumidor com `max-attempts` 3 e um tratador que grava `efeito` e lança `IllegalStateException` na primeira tentativa e termina normalmente na segunda.
2. Publicar uma mensagem.

**Esperado:** depois da primeira tentativa `efeito` e `inbox` não contêm linha (rollback); nenhum ack antes da segunda tentativa; depois da segunda `efeito` e `inbox` têm uma linha cada, o ack sai uma vez e `inbox.process.retries` vale 1.

### TC-7 (recomendado) — Conexão em autocommit é recusada

1. Configurar um `InboxTransactionExecutor` de teste que entrega uma conexão com `autoCommit=true`.
2. Processar uma mensagem com `max-attempts` 1.

**Esperado:** o processamento falha com `IllegalStateException`; o tratador não é chamado; a tabela `inbox` fica vazia; a mensagem vai para a DLQ.

### TC-8 (obrigatório) — Ack que falha depois do commit não é falha de processamento

1. Com a `MessageSource` simulada, fazer `ack` lançar `IOException` na primeira entrega de uma mensagem nova.
2. Processar a mensagem e depois reentregá-la (mesmo `messageId`), como o broker faz.

**Esperado:** o tratador roda uma vez, a primeira entrega registra um log de aviso do ack e não incrementa `inbox.process.retries` nem `inbox.dlq.messages`; a reentrega é descartada pela inbox com ack e `inbox.duplicates.discarded` vale 1.

## História 3 — Dedup, corrida e queda entre commit e ack (R5 a R7)

### TC-9 (obrigatório) — Mensagem repetida é descartada com ack

1. Processar uma mensagem nova (tratador chamado, inbox com uma linha).
2. Entregar uma segunda mensagem com o mesmo `messageId`.

**Esperado:** o tratador é chamado uma única vez; `efeito` e `inbox` mantêm uma linha; a segunda entrega recebe ack; `inbox.duplicates.discarded` vale 1.

### TC-10 (obrigatório) — Duas entregas simultâneas do mesmo `messageId` (corrida), em H2 e em SQL Server

1. Montar duas entregas com o mesmo `messageId` e `aggregateKey` diferentes (para que a serialização por chave não as ordene), com um tratador que dorme 200 ms depois de gravar `efeito`.
2. Entregá-las ao mesmo tempo a duas threads de trabalho.

**Esperado:** `efeito` e `inbox` têm uma linha cada; uma entrega termina aplicada, a outra é descartada como duplicata (a transação dela é desfeita pela violação da chave primária ou pela consulta); as duas recebem ack; nenhuma vai para a DLQ nem conta como retry; `inbox.duplicates.discarded` vale 1. O caso roda sobre H2 (`*Test` e `*IT`) e sobre SQL Server (`*IT`).

### TC-11 (obrigatório) — Consumidor cai entre o commit e o ack (A-F2)

1. Com RabbitMQ e Toxiproxy, iniciar o consumidor por um proxy; o tratador de teste, depois do commit e antes do ack, derruba a conexão pelo Toxiproxy.
2. Publicar uma mensagem; restabelecer o proxy; aguardar a reentrega pelo broker.

**Esperado:** a mensagem é reentregue com `redelivered=true`; a inbox detecta o `messageId` e a descarta com ack; `efeito` tem uma linha; a fila principal fica sem mensagens e sem mensagens sem ack; `inbox.duplicates.discarded` vale 1.

### TC-12 (recomendado) — Violação de unicidade do tratador é falha, não duplicata

1. Configurar um tratador que, na primeira tentativa, insere em `efeito` um valor que viola a chave única de `efeito`, e nas seguintes insere um valor válido.
2. Processar uma mensagem nova.

**Esperado:** a primeira tentativa é falha de processamento (rollback, `inbox.process.retries` vale 1, nenhum ack); a segunda aplica o efeito; `inbox.duplicates.discarded` vale 0.

## História 4 — Ordem por `aggregateKey` (R8, R8a)

### TC-13 (obrigatório) — Mesma chave: ordem de entrega, uma por vez

1. Entregar 100 mensagens da chave `A`, numeradas, com `workers` 8 e um tratador que dorme um tempo aleatório e registra início e fim de cada uma.
2. Aguardar o fim.

**Esperado:** o início de cada mensagem da chave é posterior ao fim e ao ack da anterior; a ordem de início é a ordem de entrega; nunca há duas mensagens da chave `A` em execução ao mesmo tempo. Com RabbitMQ (`*IT`), a ordem de entrega é a de publicação.

### TC-14 (obrigatório) — Chaves diferentes em paralelo

1. Entregar 8 mensagens, cada uma de uma chave diferente, com `workers` 8 e um tratador que bloqueia até as 8 estarem em execução ao mesmo tempo (com tempo limite de 5 s).

**Esperado:** as 8 chegam a estar em execução simultaneamente; nenhuma estoura o tempo limite.

### TC-15 (obrigatório) — Mensagens aguardando a chave contam no limite em voo

1. Iniciar o consumidor com `max-in-flight` 10 contra RabbitMQ, com um tratador bloqueado na primeira mensagem de uma chave e 100 mensagens dessa mesma chave na fila.
2. Consultar na gestão do broker o número de mensagens sem ack do consumidor.

**Esperado:** o número de mensagens sem ack é 10 e não passa disso; as demais continuam na fila principal. Sem Docker, a `MessageSource` simulada registra que o `start` recebeu `maxInFlight` 10.

### TC-16 (recomendado) — Estrutura da chave é liberada

1. Processar uma mensagem de cada uma de 1 000 chaves diferentes.
2. Ao fim, inspecionar o número de chaves mantidas pelo `KeyedExecutor`.

**Esperado:** zero chaves mantidas.

## História 5 — Retry com backoff e DLQ (R9 a R11, R10a, R10b)

### TC-17 (obrigatório) — Intervalos do backoff

1. Iniciar o consumidor com `initial` 100 ms, `multiplier` 2,0, `max` 300 ms e `max-attempts` 5, com um relógio simulado e um tratador que falha sempre.
2. Entregar uma mensagem e registrar o instante de cada tentativa.

**Esperado:** tentativas 2, 3, 4 e 5 ocorrem 100 ms, 200 ms, 300 ms e 300 ms depois da tentativa anterior (`min(max, initial × multiplier^(n-1))`); `inbox.process.retries` vale 4.

### TC-18 (obrigatório) — Outras chaves seguem durante o retry; a mesma chave espera

1. Entregar `A1` (falha nas 2 primeiras tentativas e passa na terceira), depois `A2` (mesma chave) e `B1` (outra chave).

**Esperado:** `B1` é aplicada enquanto `A1` espera o backoff; `A2` só começa depois de `A1` ser aplicada; a ordem de aplicação da chave `A` é `A1`, `A2`.

### TC-19 (obrigatório) — Mensagem veneno vai para a DLQ após o limite (A-F6)

1. Com RabbitMQ, publicar uma mensagem cujo tratador falha sempre, com `max-attempts` 3.
2. Aguardar.

**Esperado:** o tratador é chamado 3 vezes; a DLQ contém uma mensagem com o mesmo corpo e os headers originais mais `x-inbox-failure` = `max-attempts` e `x-inbox-attempts` = `3`; a fila principal fica sem a mensagem e sem mensagens sem ack; `inbox` fica sem linha; `inbox.dlq.messages` vale 1.

### TC-20 (obrigatório) — Mensagens seguintes da chave também vão para a DLQ; outras chaves seguem (A-F6)

1. Com RabbitMQ, publicar `A1` (veneno), `A2`, `A3` (da mesma chave) e `B1` (outra chave), nessa ordem.
2. Aguardar.

**Esperado:** a DLQ recebe `A1`, `A2` e `A3` (as duas últimas com `x-inbox-failure` = `blocked-key` e `x-inbox-attempts` = `0`); o tratador não é chamado para `A2` e `A3`; `B1` é aplicada (linha em `efeito` e em `inbox`); `inbox.dlq.messages` vale 3.

### TC-21 (obrigatório) — Falha na publicação da DLQ mantém a original sem ack

1. Com a `MessageSource` simulada, fazer `deadLetter` lançar `IOException` nas 2 primeiras chamadas de uma mensagem veneno com `max-attempts` 1.
2. Aguardar a terceira chamada.

**Esperado:** a original não recebe ack nas duas primeiras chamadas; as chamadas são repetidas com os intervalos do backoff; `inbox.process.retries` não aumenta por causa delas; na terceira chamada `deadLetter` conclui e `inbox.dlq.messages` vale 1.

### TC-22 (recomendado) — DLQ preserva corpo e headers

1. Enviar uma mensagem veneno à DLQ (TC-19).
2. Ler a mensagem da DLQ.

**Esperado:** o corpo é idêntico ao original, os oito headers do contrato são iguais e `x-inbox-failure` e `x-inbox-attempts` estão presentes.

### TC-23 (obrigatório) — Configuração inválida é recusada, inclusive `NaN`

1. Para cada valor — `max-attempts` 0, `backoff.initial` 0, `backoff.multiplier` `NaN`, `Infinity`, `0.5`, `backoff.max` menor que `initial`, `max-in-flight` 0, `workers` 0, fila em branco, DLQ igual à fila — chamar `InboxConfig.fromProperties` (ou construir `RetryBackoff`).
2. Chamar também com `inbox.consumer.queue` ausente.

**Esperado:** `IllegalArgumentException` para cada caso, com a chave recusada na mensagem; com os valores válidos de referência a criação conclui.

### TC-24 (recomendado) — Mensagem sem `aggregateKey` legível usa a chave reservada

1. Entregar duas mensagens sem o header `aggregateKey`, com `max-attempts` 1.

**Esperado:** a primeira vai para a DLQ com `x-inbox-failure` = `max-attempts`; a segunda vai para a DLQ com `x-inbox-failure` = `blocked-key`; uma terceira mensagem válida de outra chave é aplicada.

### TC-25 (recomendado) — Reinício libera as chaves bloqueadas (limite da P7)

1. Levar a chave `A` ao bloqueio (TC-20), fechar o `InboxConsumer` e criar outro com um `InMemoryBlockedKeys` novo.
2. Entregar `A4`.

**Esperado:** `A4` é processada normalmente, porque o estado de bloqueio não sobrevive ao reinício; o caso documenta o limite registrado na P7 de `pendencias.md` e no README do componente.

## História 6 — Dialetos H2 e SQL Server (R12)

### TC-26 (obrigatório) — Esquema H2, idempotente

1. Executar `InboxSchemaInstaller.install(dataSource, H2InboxDialect)` duas vezes sobre um H2 vazio.
2. Ler os metadados da tabela `inbox`.

**Esperado:** as duas execuções concluem; existe `inbox` com `message_id` `UUID` chave primária e `consumed_at` `TIMESTAMP` não anulável com padrão `CURRENT_TIMESTAMP`.

### TC-27 (obrigatório) — Esquema SQL Server, idempotente (com Docker)

1. Executar `InboxSchemaInstaller.install(dataSource, SqlServerInboxDialect)` duas vezes sobre um SQL Server vazio.
2. Ler os metadados de `inbox`.

**Esperado:** as duas execuções concluem; `message_id` é `uniqueidentifier` chave primária e `consumed_at` é `datetime2` não anulável com padrão `SYSUTCDATETIME()`, com a mesma estrutura da tabela do `sql/outbox.sql` do `demooutboxinbox`.

### TC-28 (obrigatório) — Violação de chave primária reconhecida em cada dialeto

1. Em cada dialeto, inserir o mesmo `message_id` duas vezes na mesma conexão.
2. Passar a `SQLException` da segunda inserção a `isDuplicateKey`; passar também uma `SQLException` de outro erro (por exemplo, tabela inexistente).

**Esperado:** `isDuplicateKey` devolve `true` para a violação (H2: `SQLState` `23505`; SQL Server: código 2627) e `false` para o outro erro; `exists` devolve `true` para o `message_id` gravado e `false` para um novo.

### TC-29 (recomendado) — `consumed_at` do H2 em UTC

1. Em uma JVM com `-Duser.timezone=America/Sao_Paulo`, inserir uma linha pelo `H2InboxDialect` e ler `consumed_at`.
2. Comparar com `LocalDateTime.now(ZoneOffset.UTC)`.

**Esperado:** a diferença é de segundos, não de horas.

## História 7 — Métricas e logs (R15, R16)

### TC-30 (obrigatório) — Contadores registrados e incrementados

1. Criar um `InboxMetrics` sobre um `SimpleMeterRegistry`.
2. Percorrer uma duplicata (TC-9), uma repetição (TC-6) e uma ida à DLQ (TC-19).

**Esperado:** o registro contém os contadores `inbox.duplicates.discarded`, `inbox.dlq.messages` e `inbox.process.retries` (os nomes das constantes públicas) com os valores 1, 1 e a contagem de repetições do cenário; nenhum medidor tem valor-meta.

### TC-31 (obrigatório) — MDC em todo o processamento

1. Capturar os logs (`LogCapture`) e processar uma mensagem que falha uma vez e depois passa, com um tratador que escreve um log.
2. Ler o MDC de cada evento de log e o MDC da thread de trabalho depois do fim.

**Esperado:** todos os eventos do processamento, inclusive o da repetição, trazem `messageId`, `correlationId`, `aggregateKey` e `traceId` da mensagem; depois do fim o MDC da thread de trabalho não contém as quatro chaves.

### TC-32 (recomendado) — MDC parcial para mensagem ilegível

1. Processar uma mensagem com os headers `aggregateKey` e `traceId` e sem `messageId`.

**Esperado:** os eventos de log trazem só `aggregateKey` e `traceId`; nenhuma chave ausente aparece como texto `null`.

## História 8 — Memória (R17)

### TC-33 (obrigatório) — Memória estável sob carga (com Docker)

1. Executar o `InboxMemoryIT` com o failsafe (`-Xmx256m -XX:MaxDirectMemorySize=64m`): publicar 10 000 mensagens em 100 chaves e consumi-las com `max-in-flight` 50 e um tratador que só grava a inbox.
2. Medir o heap em 2 000, 6 000 e 10 000 mensagens consumidas, como o `RelayMemoryIT`.

**Esperado:** as 10 000 mensagens são aplicadas uma vez cada; o crescimento de heap entre o segundo e o terceiro ponto é menor que 32 MiB; nunca há mais de 50 mensagens sem ack.

### TC-34 (obrigatório) — `basicQos` igual ao limite em voo (com Docker)

1. Iniciar o consumidor com `max-in-flight` 7 e publicar 100 mensagens com o tratador bloqueado.
2. Consultar o `prefetch_count` do consumidor na gestão do broker.

**Esperado:** `prefetch_count` é 7.

## História 9 — Integração Spring

### TC-35 (obrigatório) — Consumidor criado com handler e fila

1. Subir o contexto de teste com `inbox.consumer.queue=fila.teste`, um `DataSource` H2 com a tabela `inbox`, um `DataSourceTransactionManager`, a `MessageSource` simulada e um bean `InboxMessageHandler`.
2. Consultar o contexto e entregar uma mensagem pela `MessageSource` simulada.

**Esperado:** existe um bean `InboxConsumer` iniciado (a `MessageSource` simulada recebeu `start` com `fila.teste` e `fila.teste.dlq`); o handler recebe o envelope; ao fechar o contexto o consumidor é fechado.

### TC-36 (obrigatório) — Sem handler ou sem fila, nenhum consumidor

1. Subir o contexto sem bean `InboxMessageHandler`; subir outro com o bean e sem `inbox.consumer.queue`.
2. Subir um terceiro contexto só com a `OutboxAutoConfiguration` e as suas propriedades.

**Esperado:** nenhum bean `InboxConsumer` nos dois primeiros e o contexto sobe; o terceiro sobe como antes, com os beans da outbox e sem os do consumidor.

### TC-37 (recomendado) — Beans da aplicação não são substituídos

1. Subir o contexto com beans próprios de `InboxDialect`, `InboxTransactionExecutor`, `BlockedKeys`, `InboxMetrics` e `MessageSource`.

**Esperado:** o contexto usa os beans da aplicação, e a auto-configuração não registra os seus.

### TC-38 (recomendado) — Produto de banco não suportado

1. Subir o contexto com `inbox.consumer.queue`, um handler e um `DataSource` cujo `getDatabaseProductName` seja `PostgreSQL`.

**Esperado:** a subida falha com mensagem que nomeia `PostgreSQL` e os dois produtos suportados (`Microsoft SQL Server` e `H2`).

### TC-39 (obrigatório) — Handler executa dentro da transação Spring

1. No handler de teste, chamar `TransactionSynchronizationManager.isActualTransactionActive()`, gravar em `efeito` por um `JdbcTemplate` e lançar exceção na primeira tentativa.
2. Entregar uma mensagem.

**Esperado:** a transação está ativa no handler; depois da exceção `efeito` e `inbox` não têm linha; depois da tentativa que passa, as duas têm uma linha, gravadas pela mesma conexão (o `JdbcTemplate` e o registro da inbox participam da mesma transação).

### TC-40 (obrigatório) — Mesmo código nas duas linhas do Spring Boot

1. Na raiz, executar `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify`.

**Esperado:** compila com `--release 17` e passa nos dois módulos, com os testes do código comum executados em cada um.

## História 10 — Documentação e versão

### TC-41 (obrigatório) — Todos os artefatos em 0.4.0-SNAPSHOT

1. Na raiz, executar `mvn -o package -DskipTests`.
2. Procurar `0.3.0-SNAPSHOT` em `pom.xml`, `README.md`, `AGENTS.md` e skills das pastas do componente e do demo, fora das specs e do registro histórico de decisões.

**Esperado:** o build do agregador conclui; os artefatos `mensageria-outbox`, `mensageria-outbox-core`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` têm a versão 0.4.0-SNAPSHOT; o passo 2 não encontra ocorrência.

### TC-42 (recomendado) — README e ADR

1. Abrir `mensageria-outbox/README.md` e `mensageria-outbox/docs/adr/README.md`.

**Esperado:** o README descreve dependência, tabela, filas, tratador, transação, configuração `inbox.consumer.*`, métricas, limites e o limite do bloqueio de chaves (P7); o índice de ADRs lista o `0002` e o arquivo existe com contexto, decisão e consequências.

### TC-43 (recomendado) — Documentação autoritativa atualizada

1. Abrir cada documento de "Impacto na documentação autoritativa" do [`plan.md`](./plan.md).

**Esperado:** cada documento descreve o consumidor no `mensageria-outbox-core`, no mesmo contexto de beans da outbox, com Java 8 como piso do `core`, o SQL Server e o H2 como bancos da inbox e as pendências P6, P1 do código comum e P2 dos adaptadores removidas; a P7 permanece.
