# Pesquisa: Mensageria Outbox — núcleo da inbox no `mensageria-outbox-core`

Contexto, alternativas e evidências das decisões do [`plan.md`](./plan.md). Ambiente da investigação: Maven 3.9.9, JDK 8 (1.8.0_202), 11 (corretto 11.0.25), 17 (17.0.20), 21 (corretto 21.0.6) e 25 (25.0.4.1) instalados; sem Docker.

## Artefato, pacote e piso de Java

**Contexto.** O requisitante decidiu em 2026-10-21 que o consumidor fica no `mensageria-outbox-core`, sem artefato novo para importar, e que o contexto de beans do Spring é o da outbox. O `core` tem Java 8 como piso, com `maven.compiler.release` 8 e a regra `enforceBytecodeVersion` (`maxJdkVersion` 8) herdadas do POM `mensageria-outbox`.

**Alternativas:**

- **Pacote `br.com.db1.poc.inbox` dentro do `core`** — sem POM novo; o piso e o enforcer do `core` valem para o código novo.
- **Módulo Maven separado com o piso Java 21 da skill** — descartado pelo requisitante.
- **Subir o piso do `core` para Java 21** — quebra a decisão 002 (Java 8 como piso, matriz de JDKs 8 a 25) e os adaptadores Spring de piso 17; nenhuma fonte pede isso.

**Decisão.** Pacote base `br.com.db1.poc.inbox` no `core`, em Java 8, com os subpacotes `dialect`, `rabbit`, `schema` e `metrics`; o requisitante definiu esse pacote em 2026-10-21. O que é comum à inbox e à outbox vem do pacote `br.com.db1.poc.shared` (unidade 004), e nenhum dos dois lados importa o outro. A integração Spring fica em `br.com.db1.poc.outbox.spring`, junto da `OutboxRabbitConnectionFactoryConfiguration` package-private.

**Evidência.** `mensageria-outbox/pom.xml` (`maven.compiler.release` 8, enforcer 8); `core/pom.xml`; `PackageBoundaryTest` (RabbitMQ só em `br/com/db1/poc/outbox/rabbit`, Spring proibido em todo `src/main`; a unidade 004 o amplia para `shared/rabbit`, `outbox/rabbit` e `inbox/rabbit` e para a direção das dependências); `TestInfrastructure`, `LogCapture` e `Await` ficam em `shared.support` (unidade 004); estrutura de pacotes do `core`.

**Consequências.** O consumidor não usa `record`, `var`, `switch` por expressão, blocos de texto nem API posterior ao Java 8. A skill `mensageria-inbox` e o `functional-map.md` citam Java 21 para o consumidor; a divergência é desvio deliberado, registrado em "Impacto na documentação autoritativa" do plano.

## Envelope de leitura

**Contexto.** A skill diz que o módulo carrega "a própria definição do envelope"; a razão era a independência entre dois módulos. Com o consumidor no `core`, existe uma definição única, `shared.envelope.Envelope`, com `schemaVersion` `short`, texto obrigatório e limites de tamanho iguais às colunas da outbox.

**Alternativas:**

- **Reaproveitar `shared.envelope.Envelope`** — um tipo só; a leitura passa pelas mesmas validações do produtor.
- **Tipo próprio do consumidor** — duplica campos e validações sem uma razão de independência.

**Decisão.** `EnvelopeReader` produz `shared.envelope.Envelope`; uma violação das validações do `Envelope` (texto vazio, acima de 200, 100, 100 ou 64 caracteres, `schemaVersion` fora de `short`) é `InvalidEnvelopeException`, e a mensagem segue o retry e a DLQ.

**Evidência.** `Envelope.java` (construtor valida campos e tamanhos); `RabbitMessagePublisher` (publica `schemaVersion` como `int` e `occurredAt` com `OCCURRED_AT_FORMAT`); contrato da mensagem publicada (headers e tipos).

**Consequências.** Uma mensagem de um produtor que publique texto maior do que o `Envelope` aceita vai para a DLQ em vez de chegar ao tratador. Os valores dos headers que o `amqp-client` entrega como `LongString` são convertidos para `String` pelo adaptador RabbitMQ antes de chegarem à porta de consumo, e `schemaVersion` chega como `Number`.

**Evidência da conversão.** `BrokerIT.Received.header` usa `String.valueOf(headers.get(name))` para ler headers recebidos, e `MovimentoConsumoService` do demo faz o mesmo com `texto(...)`.

## Versão do H2 e verificação de duplicidade

**Contexto.** A inbox em H2 precisa de driver nos testes do `core` (Java 8) e nos testes dos adaptadores (Java 17).

**Alternativas:** H2 2.2.224, 2.3.232 e 2.4.240 (disponíveis em `~/.m2/repository/com/h2database/h2`).

**Decisão.** `com.h2database:h2` 2.2.224 em escopo `test` no `core`; nos adaptadores, a versão gerenciada pelo BOM do Spring Boot, em escopo `test`.

**Evidência.**

- Bytecode: `org/h2/Driver.class` major 52 (Java 8) no 2.2.224 e major 55 (Java 11) no 2.3.232; o enforcer `enforceBytecodeVersion` do `core` recusa o 2.3.232.
- Execução do 2.2.224 com uma sonda (tabela `inbox (message_id UUID PRIMARY KEY, consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)`, `setObject(UUID)`, duas inserções do mesmo `message_id`) nos JDKs 8 (1.8.0_202), 11 (11.0.25), 17 (17.0.20), 21 (21.0.6) e 25 (25.0.4.1): em todos a segunda inserção lançou `SQLException` com `SQLState` `23505` e o `commit` concluiu.
- SQL Server: `SqlServerOutboxDialect` reconhece a violação de chave pelos códigos 2627 e 2601 e liga UUID como texto (`setString`).

- `consumed_at` em UTC: com `-Duser.timezone` `America/Sao_Paulo` e `Asia/Tokyo`, o padrão `CURRENT_TIMESTAMP` e a expressão `CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS TIMESTAMP)` gravaram o horário local da JVM (15:13 e 03:13 para 18:13 UTC) no 2.2.224, nos JDKs 8 e 25; só com `-Duser.timezone=UTC` o valor foi UTC. Nenhuma expressão de padrão testada entrega UTC, então `H2InboxDialect.insert` informa `consumed_at` com `LocalDateTime` em UTC.

**Consequências.** O H2 não é dependência de compilação nem de execução do `core`: `H2InboxDialect` usa só JDBC e `SQLState`. A aplicação hospedeira traz o próprio driver.

## Ordem das operações na transação

**Contexto.** A skill exige efeito e inbox na mesma transação e ack depois do commit; a ordem interna não é definida.

**Alternativas:**

- **Consulta, inserção na inbox e depois o tratador** — numa corrida do mesmo `messageId`, a segunda transação bloqueia na inserção (chave primária) até a primeira confirmar e falha antes de executar o efeito; a violação é a duplicata.
- **Consulta, tratador e depois a inserção** — a segunda transação executa o efeito e só falha no fim, e o efeito (por exemplo, chamadas externas do tratador) já aconteceu.

**Decisão.** Consulta por `message_id`; se existe, duplicata sem tratador; senão inserção na inbox e, em seguida, o tratador. A violação de chave primária na inserção desfaz a transação e é duplicata; exceção do tratador desfaz a transação e é falha de processamento.

**Evidência.** `MovimentoConsumoService` do demo faz a consulta e a inserção na mesma transação e trata a violação como duplicata na corrida; as duas transações do mesmo `messageId` serializam pela chave primária no SQL Server e no H2.

**Consequências.** Um tratador que lança uma violação de unicidade do próprio dado de negócio é falha de processamento (retry e DLQ), porque só a exceção da inserção na inbox é capturada como duplicata.

## Serialização por chave, retry e limite em voo

**Contexto.** R8, R8a e R10a: uma mensagem por chave por vez; a chave espera durante o retry; mensagens aguardando a vez contam como em voo. O `amqp-client` entrega os callbacks do consumidor em um pool próprio, e uma mensagem sem ack consome uma vaga do `basicQos`.

**Alternativas:**

- **Fila de tarefas por chave com um pool de threads de trabalho comum e retry agendado em um agendador** — a espera do backoff não ocupa thread de trabalho; a chave retém a vez até a mensagem terminar.
- **Uma thread por chave** — a memória cresce com o número de chaves.
- **`basicNack` com `requeue` para o retry** — a mensagem perde a vez na chave e consome o limite de entregas da fila quorum (20), como a P6 de `pendencias.md` descreve.

**Decisão.** `KeyedExecutor` com `workers` threads e um mapa de chave para fila; a chave é removida quando a fila esvazia; o retry reagenda a mesma tarefa em um `ScheduledExecutorService` e a chave permanece ocupada; o limite de mensagens em voo é o `basicQos` (`max-in-flight`), de modo que o callback de entrega só enfileira e nunca bloqueia.

**Evidência.** Skill `mensageria-inbox` R8, R8a, R9, R10a; P6 de `pendencias.md` (descarte do `requeue`); o `basicQos` (prefetch) limita as entregas sem ack que o broker envia ao consumidor, conforme a documentação do RabbitMQ; o teste `InboxMemoryIT` e o teste de serialização por chave verificam o limite.

**Consequências.** Uma chave em retry segura as suas mensagens sem ack e elas ocupam vagas de `max-in-flight` (risco declarado na spec).

## DLQ e chaves bloqueadas

**Contexto.** R10, R10b e R11. O requisitante registrou como pendência (P7) o tratamento do estado das chaves bloqueadas além da vida do processo e a liberação.

**Alternativas** para o armazenamento do bloqueio: memória (escolhida para esta unidade, atrás de uma interface), tabela no banco do consumidor liberada por método da biblioteca ou por `DELETE` manual (P7, sem decisão).

**Decisão.** `BlockedKeys` (interface) com `InMemoryBlockedKeys` como única implementação; sem liberação durante a execução. A publicação na DLQ usa o exchange padrão (`""`) com a DLQ como routing key, `mandatory=true` e confirm positivo antes do ack da original; a falha de publicação mantém a original sem ack e repete a publicação com os intervalos do backoff, sem contar tentativa de processamento.

**Evidência.** Resposta do requisitante a g1 (P7); contrato da mensagem publicada (headers preservados); skill R11 (DLQ declarada pela aplicação junto da principal).

**Consequências.** O limite da memória está na P7 e no README do componente; a interface `BlockedKeys` é o ponto onde a decisão futura entra sem alterar o consumidor. A mensagem na DLQ recebe `x-inbox-failure` e `x-inbox-attempts` para a revisão manual.

## Chave de serialização de mensagem sem `aggregateKey` legível

**Contexto.** Header ausente é falha de processamento (P6), mas a serialização precisa de uma chave antes de o envelope ser lido.

**Decisão.** A chave é o header `aggregateKey` quando existe e não está vazio; do contrário, a chave reservada `<sem-aggregateKey>`. Com todas as mensagens ilegíveis sob uma chave, a primeira que esgota as tentativas bloqueia só essa chave reservada, e as seguintes ilegíveis vão direto para a DLQ.

**Consequências.** O `Envelope` não proíbe esse texto: uma aplicação que o use como `aggregateKey` compartilharia a chave com as mensagens ilegíveis. O texto escolhido não é um identificador de agregado plausível.

## Parâmetros e padrões

**Contexto.** A skill e a P6 declaram que os padrões de tentativas, backoff, mensagens em voo e filas são decididos nesta unidade.

**Decisão.** `max-attempts` 5; backoff 200 ms, fator 2,0, teto 30 s, os mesmos padrões da publicação da outbox (`OutboxRelayProperties`); `max-in-flight` 50; `workers` 8; `confirm-timeout` 5 s (o mesmo da outbox); DLQ `<queue>.dlq`. Nenhum é meta de desempenho; todos são configuráveis.

**Evidência.** `OutboxRelayProperties` (`200ms`, `2.0`, `30s`, `5s`); skill `mensageria-inbox`, "Parâmetros configuráveis".

**Consequências.** O pior atraso de uma mensagem antes da DLQ com os padrões é 200 ms + 400 ms + 800 ms + 1,6 s = 3 s de espera acumulada. `max-attempts` conta a primeira tentativa.

## Validação do backoff (`NaN`)

**Contexto.** A P3 de `pendencias.md` registra que `RelayConfig` aceita `backoff.multiplier=NaN`.

**Decisão.** `InboxConfig` e `RetryBackoff` recusam multiplicador `NaN`, infinito ou menor que 1,0 na criação; a P3 do `core` permanece fora desta unidade.

**Consequências.** O consumidor não herda o defeito; o relay mantém o comportamento registrado.

## Spring: auto-configuração do consumidor

**Contexto.** O requisito é o mesmo contexto de beans da outbox. A `OutboxAutoConfiguration` usa `@ConditionalOnMissingBean`, `@EnableConfigurationProperties` com um record e importa a `OutboxRabbitConnectionFactoryConfiguration`, a única classe que difere entre as linhas do Spring Boot.

**Alternativas:**

- **Acrescentar os beans do consumidor à `OutboxAutoConfiguration`** — uma aplicação só consumidora (Extrato) receberia o publicador e o exchange da outbox.
- **`InboxAutoConfiguration` separada, registrada no `AutoConfiguration.imports`, com `@Import` da mesma `OutboxRabbitConnectionFactoryConfiguration`** — cada lado sobe só quando a aplicação o usa; o bean `outboxRabbitConnectionFactory` é o mesmo, porque o Spring importa a classe uma vez.

**Decisão.** `InboxAutoConfiguration` separada. O `InboxConsumer` é criado com `@ConditionalOnBean(InboxMessageHandler.class)` e `@ConditionalOnProperty(prefix = "inbox.consumer", name = "queue")`; o ciclo de vida é um `SmartLifecycle`. O `InboxDialect` é escolhido pelo `DatabaseMetaData.getDatabaseProductName` (`Microsoft SQL Server` ou `H2`); outro produto falha a subida.

**Evidência.** `OutboxAutoConfiguration.java` (padrões do código comum); `OutboxRabbitConnectionFactoryConfiguration.java` do `-spring-boot4` (fábrica com recuperação automática ligada); `OutboxGravador` (conexão JDBC da transação pelo `DataSourceUtils`); `mensageria-outbox-spring-boot-shared/AGENTS.md` (todo bean com `@ConditionalOnMissingBean`; o que difere entre as linhas fica no módulo).

**Consequências.** O nome do bean da fábrica de conexões continua `outboxRabbitConnectionFactory`. O código comum compila nas duas linhas do Spring Boot com APIs disponíveis em ambas (`SmartLifecycle`, `TransactionTemplate`, `DataSourceUtils`, `ObjectProvider`).

## Topologia: declaração das filas

**Decisão.** A aplicação declara a fila principal e a DLQ; `shared.rabbit.RabbitTopology.declareQuorumQueue(Channel, String)` declara a DLQ quorum e durável sem ligação a exchange; o consumidor confirma as duas com `queueDeclarePassive` na inicialização e falha com mensagem que nomeia a fila ausente.

**Evidência.** Skill `mensageria-inbox` R11 e "Filas" (declaradas pela aplicação); `RabbitTopology.declareQuorumQueue` (argumentos `x-queue-type=quorum`); contrato da mensagem publicada ("todo declarante repete exatamente esses argumentos").
