# 0002. Desenhar o consumidor da inbox no `mensageria-outbox-core`

- **Status:** Proposto
- **Data:** 2026-10-21
- **Decisores:** pessoa decisora da PoC (aceite pendente); o requisitante da unidade `003-mensageria-outbox-nucleo-inbox` decidiu o artefato, o pacote, o Java 8 como piso e o estado das chaves bloqueadas em memória

## Contexto

O lado consumidor da entrega atômica (R1 a R18 da skill `mensageria-inbox`) precisa receber as mensagens da fila principal com ack manual, gravar o efeito de negócio e o `messageId` na mesma transação local, descartar duplicatas, aplicar as mensagens de um `aggregateKey` uma por vez e na ordem de entrega, repetir com backoff a mensagem que falha e enviá-la à DLQ ao exceder o limite de tentativas. O consumidor serve ao `demooutboxinbox` (SQL Server) e à aplicação Extrato (H2), em aplicações Spring Boot 3.x e 4.x e fora do Spring.

As restrições de origem: sem XA, sem JTA e sem transação distribuída; broker conhecido só por uma interface (R14); inbox isolada por dialeto (R12); nenhuma meta de desempenho (R18); consumo sem crescimento de memória (R17); o `core` tem Java 8 como piso e a matriz de JDKs 8, 11, 17, 21 e 25 (unidade `002`). A mensagem consumida é a do contrato `001/contracts/mensagem-publicada.md`.

## Alternativas consideradas

### Artefato, pacote e piso de Java

- **Pacote `br.com.db1.poc.inbox` no `mensageria-outbox-core`**, em Java 8. Sem POM novo; o piso e o enforcer de bytecode do `core` valem para o código novo, e o consumidor usa o mesmo contexto de beans da outbox. Custo: o código do consumidor não usa `record`, `var`, `switch` por expressão nem API posterior ao Java 8.
- **Módulo Maven separado com Java 21**, como a skill descrevia. Descartado pelo requisitante.
- **Subir o piso do `core` para Java 21.** Quebra a unidade `002` e os adaptadores Spring de piso 17; nenhuma fonte pede isso.

### Envelope de leitura

- **Reaproveitar `shared.envelope.Envelope`.** Um tipo só, com as mesmas validações e os mesmos limites de tamanho do produtor.
- **Tipo próprio do consumidor.** Duplica campos e validações sem razão de independência, porque os dois lados vivem no mesmo artefato.

### Versão do H2 e instante de `consumed_at`

- **H2 2.2.224 em escopo `test`** no `core`: bytecode Java 8 (major 52) e execução verificada nos JDKs 8, 11, 17, 21 e 25.
- **H2 2.3.232 ou 2.4.240**: bytecode Java 11 ou superior, recusado pelo enforcer do `core`.
- **`consumed_at` pelo padrão `CURRENT_TIMESTAMP`**: grava o horário da zona da JVM (observado com `America/Sao_Paulo` e `Asia/Tokyo`); nenhuma expressão de padrão testada entrega UTC.
- **`consumed_at` informado pelo dialeto** com `LocalDateTime` em UTC.

### Ordem das operações na transação

- **Consulta do `messageId`, inserção na inbox e depois o tratador.** Numa corrida do mesmo `messageId`, a segunda transação bloqueia na inserção até a primeira confirmar e falha antes de executar o efeito.
- **Consulta, tratador e depois a inserção.** A segunda transação executa o efeito e só falha no fim, e o efeito (por exemplo, chamadas externas do tratador) já aconteceu.

### Serialização por chave, retry e limite em voo

- **Fila de tarefas por chave sobre um pool de threads de trabalho, com o retry agendado fora delas.** A espera do backoff não ocupa thread de trabalho e a chave retém a vez até a mensagem terminar.
- **Uma thread por chave.** A memória cresce com o número de chaves.
- **`basicNack` com `requeue` para o retry.** A mensagem perde a vez na chave e consome o limite de entregas da fila quorum (20).

### Estado das chaves bloqueadas

- **Memória do processo, atrás de uma interface.** Simples; o reinício libera as chaves.
- **Tabela no banco do consumidor**, liberada por método da biblioteca ou por `DELETE` manual. Tratamento sem decisão (P7).

### Declaração das filas

- **A aplicação declara a fila principal e a DLQ; o consumidor as confirma com `queueDeclarePassive`.** Um consumidor sem permissão de declaração não precisa de configuração extra, e uma fila ausente é erro de configuração visível na subida.
- **O consumidor declara as filas.** Repete os argumentos da fila quorum em mais um lugar e esconde configuração errada.

### Integração Spring

- **Beans do consumidor na `OutboxAutoConfiguration`.** Uma aplicação só consumidora receberia o publicador e o exchange da outbox.
- **`InboxAutoConfiguration` separada**, registrada no `AutoConfiguration.imports`, com `@Import` da mesma `OutboxRabbitConnectionFactoryConfiguration`.

## Decisão

Adotamos o consumidor no pacote `br.com.db1.poc.inbox` do `mensageria-outbox-core`, em Java 8, sem artefato novo, com as decisões abaixo.

- **Leitura do envelope.** `EnvelopeReader` converte a `Delivery` em `shared.envelope.Envelope`, lendo só os headers; header ausente, `messageId` ou `correlationId` fora do formato UUID, `occurredAt` fora de ISO-8601 UTC com sufixo `Z`, `schemaVersion` não numérico ou texto acima do tamanho máximo do `Envelope` lançam `InvalidEnvelopeException`, e a mensagem segue o retry e a DLQ. O adaptador RabbitMQ converte os headers `LongString` em `String` antes da porta de consumo; `schemaVersion` chega como `Number`.
- **Porta de consumo (R14).** `MessageSource` e `Delivery` não expõem tipo do RabbitMQ; `inbox.rabbit.RabbitMessageSource` é o único adaptador. Uma entrega recebida antes da queda do canal não vale mais: `ack`, `requeue` e `deadLetter` sobre ela lançam `StaleDeliveryException`, porque um ack com a etiqueta de entrega de um canal anterior fecharia o canal, e o consumidor abandona a cópia sem bloquear a chave; o broker devolve a mensagem à fila e a inbox descarta a reentrega já aplicada.
- **Transação (R4).** `InboxTransactionExecutor` executa o trabalho em uma transação local e entrega a conexão; `JdbcInboxTransactionExecutor` serve ao uso sem framework e `SpringInboxTransactionExecutor` ao Spring. A conexão em autocommit é recusada com `IllegalStateException`.
- **Ordem das operações.** Consulta por `message_id`; se existe, duplicata sem tratador; senão, inserção na inbox e em seguida o tratador. A violação da chave primária na inserção desfaz a transação e é duplicata; exceção do tratador desfaz a transação e é falha de processamento, inclusive uma violação de unicidade do dado de negócio.
- **Serialização por chave (R8, R8a).** `KeyedExecutor` com `workers` threads e um mapa de chave para fila; a chave sai do mapa quando a fila esvazia. O callback de entrega só enfileira; o limite de mensagens em voo é o `basicQos` (`max-in-flight`), e as mensagens que aguardam a vez da chave ou o backoff contam nele.
- **Retry (R9, R10a).** Na memória do consumidor, com a chave retendo a vez durante a espera; o intervalo depois da n-ésima falha é `min(backoff.max, backoff.initial × backoff.multiplier^(n-1))`, agendado em uma thread própria que só repassa o trabalho às threads de trabalho. `max-attempts` conta a primeira tentativa.
- **DLQ (R10, R10b, R11).** Publicação no exchange padrão com a DLQ como routing key, `mandatory=true` e confirm em canal próprio, antes do ack da original, com corpo e headers originais mais `x-inbox-failure` (`max-attempts` ou `blocked-key`) e `x-inbox-attempts`. A publicação que falha mantém a original sem ack e se repete pelos intervalos do backoff, sem contar tentativa de processamento. Depois da publicação, a chave passa a bloqueada; as mensagens seguintes dela vão para a DLQ sem tratador e sem gravar na inbox.
- **Estado das chaves bloqueadas.** `BlockedKeys` é uma interface e `InMemoryBlockedKeys` é a única implementação, sem liberação durante a execução; o reinício libera todas as chaves (P7 de `.agents/context/pendencias.md`, sem decisão).
- **Chave de uma mensagem sem `aggregateKey` legível.** A serialização usa o header quando existe e não está vazio e, do contrário, a chave reservada `<sem-aggregateKey>`; a primeira mensagem ilegível que esgota as tentativas bloqueia só essa chave, e as seguintes ilegíveis vão direto para a DLQ.
- **Dialetos (R12).** `InboxDialect` concentra o SQL (`exists`, `insert`, `isDuplicateKey` e o caminho do script de esquema), sem import de classe de driver. O SQL Server liga o UUID como texto e reconhece a violação pelos códigos 2627 e 2601; o H2 liga o UUID por `setObject`, reconhece o `SQLState` `23505` e informa `consumed_at` em UTC. O H2 2.2.224 é dependência de teste do `core`; a aplicação hospedeira traz o próprio driver. Nos adaptadores Spring, o H2 de teste tem a versão do BOM do Spring Boot.
- **Esquema.** Scripts idempotentes por dialeto, com lotes separados por `GO`, executados por `InboxSchemaInstaller`; a auto-configuração não o chama, como na outbox.
- **Parâmetros e padrões (`inbox.consumer.*`).** `max-attempts` 5; backoff de 200 ms, fator 2,0 e teto de 30 s (os da publicação da outbox); `max-in-flight` 50; `workers` 8; `confirm-timeout` 5 s; DLQ `<queue>.dlq`. Todos configuráveis e nenhum é meta de desempenho. Com os padrões, a espera acumulada antes da DLQ é de 3 s (200 ms + 400 ms + 800 ms + 1,6 s).
- **Validação do backoff.** `InboxConfig` e `RetryBackoff` recusam multiplicador `NaN`, infinito ou menor que 1,0; o `RelayConfig` da outbox mantém o comportamento da P3 de `.agents/context/pendencias.md`.
- **Topologia.** A aplicação declara a fila principal e a DLQ; `RabbitTopology.declareQuorumQueue(Channel, String)` declara a DLQ quorum durável sem ligação a exchange, e o consumidor confirma as duas com `queueDeclarePassive` na inicialização e falha com mensagem que nomeia a fila ausente.
- **Spring.** `InboxAutoConfiguration` separada da `OutboxAutoConfiguration`, com `@Import` da `OutboxRabbitConnectionFactoryConfiguration` e `@ConditionalOnMissingBean` em todo bean. O `InboxConsumer` só é criado com `inbox.consumer.queue` definida e um bean `InboxMessageHandler`, e o ciclo de vida é um `SmartLifecycle`. O dialeto é escolhido por `DatabaseMetaData.getDatabaseProductName` (`Microsoft SQL Server` ou `H2`); outro produto falha a subida com mensagem que nomeia o produto detectado e os dois suportados.

## Consequências

- Os dois lados do componente compartilham o artefato `mensageria-outbox-core`; `outbox` e `inbox` são independentes entre si e dependem de `shared`, o que o `PackageBoundaryTest` verifica. A skill `mensageria-inbox` e os documentos autoritativos descrevem o consumidor nesse artefato.
- Um produtor que publique texto maior do que o `Envelope` aceita tem a mensagem enviada à DLQ, e o tratador não a recebe.
- Uma chave em retry segura as suas mensagens sem ack, que ocupam vagas de `max-in-flight`: uma chave que falha por muito tempo reduz a vazão das demais até a DLQ.
- O reinício do consumidor libera as chaves bloqueadas e não há liberação durante a execução: a próxima mensagem de uma chave com mensagem na DLQ é aplicada sem aquela mensagem e quebra a ordem da chave. O limite consta do `README.md` do componente. A decisão futura entra por uma nova implementação de `BlockedKeys`, sem alterar o consumidor.
- Depois da queda do canal, a cópia antiga em memória é abandonada; no pior caso a mesma mensagem chega duas vezes à DLQ.
- Uma aplicação que use `<sem-aggregateKey>` como `aggregateKey` compartilharia a chave com as mensagens ilegíveis.
- A aplicação consumidora declara a fila principal e a DLQ com os argumentos de `RabbitTopology`, cria a tabela `inbox` pelo script do dialeto, define o `InboxMessageHandler` e fornece o `ConnectionFactory` com a recuperação automática de conexão.
- A aplicação só com a outbox sobe sem os beans do consumidor: nenhum deles é criado sem `inbox.consumer.queue` e sem um bean `InboxMessageHandler`.
