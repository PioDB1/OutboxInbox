# Spec: Mensageria Outbox — núcleo da inbox no `mensageria-outbox-core`

## Visão geral

Implementação do lado consumidor da entrega atômica de mensagens (consumidor com ack manual, inbox, dedup por `messageId`, serialização por `aggregateKey`, retry com backoff, DLQ, métricas e logs de consumo) dentro do artefato `mensageria-outbox-core`, com a auto-configuração Spring do consumidor nos artefatos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`, e elevação da versão de todos os artefatos do componente para 0.4.0-SNAPSHOT.

## Domínios envolvidos

A unidade atravessa os domínios abaixo porque o lado consumidor passa a viver no mesmo artefato e no mesmo contexto de beans da outbox; as tarefas tocam as pastas de cada domínio.

- **Mensageria Inbox** — regras R1 a R18 do consumo; é o domínio implementado por esta unidade. Skill: [`.agents/skills/mensageria-inbox/SKILL.md`](../../skills/mensageria-inbox/SKILL.md). Dependências técnicas: [`.agents/skills/mensageria-inbox/references/technical-dependencies.md`](../../skills/mensageria-inbox/references/technical-dependencies.md).
- **Mensageria Outbox** — hospeda o código no `core` (pacotes, POM, testes, README) e fornece o contrato da mensagem publicada, o envelope e a topologia de filas que o consumidor lê. Skill: [`.agents/skills/mensageria-outbox/SKILL.md`](../../skills/mensageria-outbox/SKILL.md) (regras R1 a R27; esta unidade não altera nenhuma delas).
- **Integração Spring Boot do componente** — beans do consumidor no código comum `mensageria-outbox-spring-boot-shared` e registro nos artefatos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`. Skills: `../../../../mensageria-outbox-spring-boot-shared/.agents/skills/mensageria-outbox-spring-boot-shared/SKILL.md`, `../../../../mensageria-outbox-spring-boot3/.agents/skills/mensageria-outbox-spring-boot3/SKILL.md` e `../../../../mensageria-outbox-spring-boot4/.agents/skills/mensageria-outbox-spring-boot4/SKILL.md`.

- **Pacote `br.com.db1.poc.shared`** — código comum à outbox e à inbox, criado pela unidade [`004-mensageria-outbox-pacote-shared`](../004-mensageria-outbox-pacote-shared/spec.md), que é precondição desta unidade; o consumidor o usa para o `Envelope`, a leitura de propriedades, o MDC, a leitura de script SQL, o reconhecimento de violação de chave do SQL Server e a topologia de filas.

Origem: P6 e P7 de [`.agents/context/pendencias.md`](../../context/pendencias.md); unidades anteriores [`001-mensageria-outbox-implementacao-modulo`](../001-mensageria-outbox-implementacao-modulo/spec.md) (outbox) e [`002-mensageria-outbox-compatibilidade-java-8`](../002-mensageria-outbox-compatibilidade-java-8/spec.md) (piso Java 8 do `core`).

## Estado atual do código

- **`mensageria-outbox/core`** (`mensageria-outbox-core`, Java 8 como piso, enforcer de bytecode 8) tem só o lado produtor: pacotes `envelope`, `dialect`, `relay`, `lease`, `publish`, `rabbit`, `retention`, `schema`, `metrics`, `write` e `internal`. A unidade [`004-mensageria-outbox-pacote-shared`](../004-mensageria-outbox-pacote-shared/spec.md) move antes o código comum à outbox e à inbox (`Envelope`, `Texts`, `RabbitTopology`, leitura de propriedades, MDC, leitura de script SQL e reconhecimento de violação de chave do SQL Server) para `br.com.db1.poc.shared`, e esta unidade parte desse estado. O `PackageBoundaryTest`, também ampliado pela unidade 004, aceita `com.rabbitmq` em `shared/rabbit`, `outbox/rabbit` e `inbox/rabbit`, proíbe Spring em todo o `src/main` e impede `outbox` e `inbox` de dependerem um do outro.
- **`mensageria-outbox-spring-boot-shared`** tem a `OutboxAutoConfiguration` (gravação na transação Spring, envio após o commit, recuperação na inicialização) e nenhum bean do consumidor. Os artefatos `-spring-boot3` e `-spring-boot4` registram só ela no `AutoConfiguration.imports` e guardam a `OutboxRabbitConnectionFactoryConfiguration`, que expõe o bean `outboxRabbitConnectionFactory` com a recuperação automática de conexão ligada.
- **`demooutboxinbox`** consome `movimentos.movimento-registrado` com código próprio (`MovimentoConsumoService`, `basicGet`, inbox em `TransactionTemplate`, ack depois do commit); a substituição é a P1 de `../../../../demooutboxinbox/.agents/context/pendencias.md` e não faz parte desta unidade.
- A versão dos artefatos está em 0.4.0-SNAPSHOT nos POMs e nos documentos do componente e do demo; o código do consumidor não existe.

## Escopo

**Dentro:**

- Receber mensagens da fila principal, ler o envelope padrão e entregar à aplicação o envelope e o payload sem interpretação.
- Aplicar o efeito de negócio e registrar o `messageId` na inbox na mesma transação local, e confirmar a mensagem ao broker só depois do commit.
- Descartar com confirmação a mensagem já consumida, inclusive quando duas entregas do mesmo `messageId` chegam ao mesmo tempo ou o consumidor cai entre o commit e a confirmação.
- Processar uma mensagem por `aggregateKey` por vez, na ordem de entrega do broker, com chaves diferentes em paralelo.
- Repetir com intervalo crescente a mensagem cujo processamento falha, mantendo a chave em espera, e enviá-la à DLQ ao exceder o limite de tentativas, com as mensagens seguintes da mesma chave.
- Guardar a inbox em H2 (aplicação Extrato) ou em SQL Server (banco do `demooutboxinbox`).
- Expor as métricas de consumo e os logs estruturados do consumo.
- Limitar as mensagens em voo e manter a memória estável sob carga.
- Disponibilizar o consumidor a aplicações Spring Boot 3.x e 4.x pelo mesmo mecanismo de auto-configuração e de beans da outbox.

**Fora:**

- O efeito de negócio e a validação semântica do payload: pertencem à aplicação consumidora.
- Tratamento do estado das chaves bloqueadas por mensagem na DLQ além da vida do processo, e a liberação da chave depois da revisão manual: P7 de [`pendencias.md`](../../context/pendencias.md), sem decisão.
- Substituição do consumo próprio do `demooutboxinbox`: P1 de `../../../../demooutboxinbox/.agents/context/pendencias.md`.
- As aplicações `extrato` e `pagamentos`, o reconciliador e a suíte de falhas completa A-F1 a A-F7.
- Reprocessamento de mensagens da DLQ.
- Qualquer meta de latência ou de throughput.
- Correção dos defeitos P1 a P5 de `pendencias.md`.

## Fronteira do domínio

**Esta spec implementa:**

- Pacote `br.com.db1.poc.inbox` do `mensageria-outbox-core`: `InboxConsumer`, `InboxConfig`, `InboxMessageHandler`, `InboxTransactionExecutor`, `JdbcInboxTransactionExecutor`, `EnvelopeReader`, `MessageSource`, `Delivery`, `BlockedKeys`, `InMemoryBlockedKeys` e as classes internas da fila de execução por chave e do backoff de consumo.
- Pacote `br.com.db1.poc.inbox.dialect` com `InboxDialect` e as implementações `sqlserver.SqlServerInboxDialect` e `h2.H2InboxDialect`.
- Tabela `inbox` e scripts de esquema por dialeto, com `br.com.db1.poc.inbox.schema.InboxSchemaInstaller`.
- Classe `br.com.db1.poc.inbox.rabbit.RabbitMessageSource` (adaptador do consumo e da DLQ) e o método `br.com.db1.poc.shared.rabbit.RabbitTopology.declareQuorumQueue(Channel, String)` para a DLQ.
- Classe `br.com.db1.poc.inbox.metrics.InboxMetrics` com os três contadores de consumo.
- Em `mensageria-outbox-spring-boot-shared`: `InboxAutoConfiguration`, `InboxProperties` (`inbox.consumer.*`), o executor transacional Spring e o ciclo de vida do consumidor; em `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`: a entrada de `InboxAutoConfiguration` no `AutoConfiguration.imports`.
- Dependência `com.h2database:h2` (escopo `test`) no `core` e nos dois adaptadores.
- Versão 0.4.0-SNAPSHOT dos artefatos `mensageria-outbox`, `mensageria-outbox-core`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`.
- `README.md` do componente com o contrato de uso do consumidor, ADR `0002` das decisões de desenho do consumidor e testes `*Test` e `*IT` do consumo.

**Pertence a outros domínios (dependência entre domínios, não vira tarefa aqui):**

- Pacote `br.com.db1.poc.shared` e os ajustes da outbox a ele → unidade 004.
- Efeito de negócio de cada mensagem consumida e a validação do conteúdo do payload → aplicação consumidora (Extrato; `demooutboxinbox`).
- Substituição de `MovimentoConsumoService`, `InboxEntity` e `InboxRepository` do demo → `demooutboxinbox`.
- Declaração da fila principal e da DLQ no broker → aplicação consumidora, com os argumentos de `RabbitTopology`.
- Verificação das invariantes A1 a A4 entre os bancos → Reconciliação.

## Histórias de usuário

1. Como equipe de uma aplicação consumidora, quero receber o envelope e o payload da mensagem sem ter de ler headers AMQP, para escrever só o efeito de negócio.
2. Como equipe da aplicação consumidora, quero que o efeito de negócio e o registro na inbox sejam confirmados juntos e que o ack só saia depois do commit, para que nenhuma mensagem seja perdida nem aplicada em duplicidade.
3. Como equipe da aplicação consumidora, quero que mensagens repetidas, simultâneas ou reentregues depois de uma queda sejam descartadas, para ter um único efeito por `messageId`.
4. Como equipe da aplicação consumidora, quero que as mensagens do mesmo `aggregateKey` sejam aplicadas na ordem de entrega e uma por vez, para que os eventos de um agregado não troquem de ordem.
5. Como operador, quero que a mensagem que falha seja repetida com backoff e vá para a DLQ ao exceder o limite, sem bloquear as outras chaves, para poder revisá-la manualmente.
6. Como equipe da aplicação consumidora, quero a inbox em H2 ou em SQL Server, para usar o banco da minha aplicação.
7. Como operador, quero métricas de consumo e logs com `messageId`, `correlationId`, `aggregateKey` e `traceId`, para analisar o consumo depois dos testes de falha.
8. Como operador, quero que o consumidor limite as mensagens em voo, para que a memória não cresça sem limite.
9. Como equipe de uma aplicação Spring Boot 3.x ou 4.x, quero que o consumidor suba por auto-configuração com um bean do tratador, como a outbox sobe, para integrar sem código de infraestrutura.
10. Como mantenedor da PoC, quero a documentação de uso e as decisões de desenho registradas e as versões elevadas para 0.4.0-SNAPSHOT, para que o componente seja adotável pelo demo e pelas aplicações.

## Critérios de aceitação

**História 1 — Leitura do envelope (R1 a R3):**

- Dada uma mensagem publicada pelo `RabbitMessagePublisher` do `core`, quando o consumidor a recebe, então o tratador recebe um `Envelope` com `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` e `traceId` iguais aos headers AMQP de mesmo nome e o `payload` igual, byte a byte, ao corpo da mensagem.
- Dada uma mensagem cujas properties AMQP `message_id`, `correlation_id` e `type` diferem dos headers, quando é lida, então o envelope usa os valores dos headers.
- Dada uma mensagem sem um dos oito headers do contrato, ou com `messageId` ou `correlationId` fora do formato UUID, ou com `occurredAt` fora de ISO-8601 UTC, ou com `schemaVersion` não numérico, ou com texto acima do tamanho máximo do `Envelope`, quando é recebida, então o processamento falha, o tratador não é chamado e a mensagem segue o retry e a DLQ da história 5.
- O módulo não interpreta o `payload`: o tratador recebe os bytes recebidos, e a validação do conteúdo é da aplicação.

**História 2 — Transação local e ack depois do commit (R4):**

- Dada uma mensagem nova, quando o consumidor a processa, então o tratador é chamado com o envelope e a conexão JDBC da transação, o `messageId` é gravado na tabela `inbox` na mesma transação, o commit ocorre e só depois o ack é enviado ao broker pelo canal que recebeu a entrega.
- Dado um tratador que lança exceção, quando o consumidor processa a mensagem, então a transação é desfeita, a tabela `inbox` não contém o `messageId` e nenhum ack é enviado antes da decisão de retry ou DLQ.
- Dada uma conexão JDBC da transação em autocommit, quando o consumidor processa a mensagem, então o processamento falha com `IllegalStateException` e nada é gravado.
- Dado um ack que falha depois do commit (canal fechado por queda do broker), quando o consumidor o envia, então a falha é registrada em log, não conta como falha de processamento e o broker reentrega a mensagem, que a inbox descarta (história 3).

**História 3 — Dedup, corrida e queda entre commit e ack (R5 a R7):**

- Dado um `messageId` já presente na inbox, quando a mensagem chega, então o tratador não é chamado, o ack é enviado, nada novo é gravado e o contador `inbox.duplicates.discarded` aumenta em 1.
- Dadas duas entregas simultâneas do mesmo `messageId` em processamento concorrente, quando as duas transações tentam gravar a inbox, então só uma confirma, o efeito do tratador existe uma única vez, a outra transação é desfeita e a mensagem correspondente recebe ack e é descartada como duplicata, sem retry e sem DLQ.
- Dado um consumidor que cai depois do commit e antes do ack (cenário A-F2 do `spec-caso-a-outbox-inbox.md`), quando o broker reentrega a mensagem, então a inbox detecta o `messageId`, a mensagem é descartada com ack e existe um único efeito.
- Uma violação de unicidade lançada pelo tratador (segunda barreira de unicidade do dado de negócio) é falha de processamento da história 5; só a violação da chave primária da `inbox` é duplicata.

**História 4 — Ordem por `aggregateKey` (R8, R8a):**

- Dadas N mensagens da mesma `aggregateKey` entregues em sequência pelo broker, quando o consumidor as processa com mais de uma thread de trabalho, então o tratador as recebe na ordem de entrega, uma por vez, e cada uma só começa depois do commit e do ack da anterior.
- Dadas mensagens de `aggregateKey` diferentes, quando o consumidor as processa, então o tratador executa mensagens de chaves diferentes ao mesmo tempo.
- Dadas mensagens aguardando a vez da sua chave, quando o limite de mensagens em voo é `max-in-flight`, então elas contam nesse limite: o consumidor nunca mantém sem ack mais de `max-in-flight` mensagens.
- Dada uma chave sem mensagens pendentes, quando a última termina, então a estrutura em memória da chave é liberada.

**História 5 — Retry com backoff e DLQ (R9 a R11, R10a, R10b):**

- Dada uma mensagem cujo processamento falha, quando ainda há tentativas, então o consumidor a repete na própria memória depois de `min(backoff.max, backoff.initial × backoff.multiplier^(n-1))` após a n-ésima falha, o contador `inbox.process.retries` aumenta em 1 a cada repetição, a transação de cada tentativa é desfeita e a chave permanece em espera.
- Dada uma chave em retry, quando chegam outras chaves, então elas são processadas durante a espera; quando chegam mensagens da mesma chave, então aguardam a anterior ser aplicada ou ir para a DLQ.
- Dada uma mensagem que falha `max-attempts` vezes, quando a última tentativa falha, então ela é publicada na DLQ com confirm positivo do broker, a original recebe ack, o contador `inbox.dlq.messages` aumenta em 1 e a chave passa a bloqueada.
- Dada uma chave bloqueada, quando chegam mensagens dela (já aguardando ou novas), então cada uma é publicada na DLQ com confirm e recebe ack, sem chamar o tratador e sem gravar na inbox, e o contador `inbox.dlq.messages` aumenta em 1 por mensagem.
- Dada uma publicação na DLQ que falha, quando o broker não confirma, então a mensagem original permanece sem ack, a publicação é repetida com os mesmos intervalos de backoff e a falha não conta como tentativa de processamento.
- A mensagem na DLQ preserva corpo e headers originais e acrescenta os headers `x-inbox-failure` (`max-attempts` ou `blocked-key`) e `x-inbox-attempts` (número de tentativas feitas; `0` para `blocked-key`).
- A configuração é recusada na criação, com `IllegalArgumentException`, quando `max-attempts` é menor que 1, quando `backoff.initial` não é positivo, quando `backoff.multiplier` é `NaN`, infinito ou menor que 1,0, quando `backoff.max` é menor que `backoff.initial`, quando `max-in-flight` ou `workers` é menor que 1, quando a fila principal ou a DLQ está em branco ou quando as duas têm o mesmo nome.
- O bloqueio de chave vale durante a vida do processo; o armazenamento além do reinício e a liberação da chave são a P7 de `pendencias.md`.

**História 6 — Dialetos H2 e SQL Server (R12):**

- Dado um banco H2, quando o script de esquema do dialeto é executado, então existe a tabela `inbox` com `message_id` `UUID` chave primária e `consumed_at` `TIMESTAMP` obrigatório com padrão `CURRENT_TIMESTAMP`; o script é idempotente, e a inserção do dialeto grava `consumed_at` com o instante UTC, qualquer que seja a zona da JVM.
- Dado um banco SQL Server, quando o script do dialeto é executado, então existe a tabela `inbox` com `message_id` `UNIQUEIDENTIFIER` chave primária e `consumed_at` `DATETIME2` obrigatório com padrão `SYSUTCDATETIME()`, com a mesma estrutura do `sql/outbox.sql` do `demooutboxinbox`; o script é idempotente.
- Dado qualquer dos dois dialetos, quando duas inserções usam o mesmo `message_id`, então a segunda lança `SQLException` que o dialeto reconhece como violação de chave primária (H2: `SQLState` `23505`; SQL Server: código 2627 ou 2601).
- O `InboxDialect` concentra todo o SQL da inbox: consulta por `message_id`, inserção e reconhecimento da violação.

**História 7 — Métricas e logs (R15, R16):**

- Dado um `MeterRegistry`, quando o `InboxMetrics` é criado, então ficam registrados os contadores `inbox.duplicates.discarded`, `inbox.dlq.messages` e `inbox.process.retries`, com os nomes públicos em constantes, sem valor-meta.
- Dada uma mensagem em processamento, quando o consumidor escreve logs, então o MDC contém `messageId`, `correlationId`, `aggregateKey` e `traceId` da mensagem em todo o processamento, também nas threads de trabalho e nas repetições, e o MDC é limpo ao final.
- Dada uma mensagem sem envelope legível, quando o consumidor escreve logs, então o MDC contém só as chaves cujos headers existem.

**História 8 — Memória (R17):**

- Dado o consumidor com `max-in-flight` N, quando o broker tem muito mais mensagens do que N, então o consumidor configura o `basicQos` com N, nunca mantém mais de N mensagens sem ack e, sob carga contínua com `-Xmx256m -XX:MaxDirectMemorySize=64m`, o uso de heap se estabiliza (teste `*IT` como o `RelayMemoryIT`).
- Os padrões de configuração são os da seção "Parâmetros" do [`plan.md`](./plan.md).

**História 9 — Integração Spring:**

- Dada uma aplicação Spring Boot 3.x ou 4.x com o artefato, um `DataSource` de H2 ou de SQL Server, um `PlatformTransactionManager`, `inbox.consumer.queue` definida e um bean `InboxMessageHandler`, quando o contexto sobe, então existe um `InboxConsumer` iniciado e parado pelo ciclo de vida do Spring, e o handler é executado dentro da transação Spring, com o registro na inbox pela mesma conexão.
- Dada uma aplicação sem bean `InboxMessageHandler` ou sem `inbox.consumer.queue`, quando o contexto sobe, então nenhum consumidor é criado e a aplicação só com a outbox sobe como antes.
- Dado um bean de qualquer tipo da auto-configuração já definido pela aplicação, então a auto-configuração não o substitui (`@ConditionalOnMissingBean`).
- Dado um `DataSource` de produto diferente de H2 e SQL Server, quando o contexto sobe com o consumidor habilitado, então a subida falha com mensagem que nomeia o produto detectado e os dois suportados.
- As chaves `inbox.consumer.*` e seus padrões constam do [`plan.md`](./plan.md), e a mesma auto-configuração compila e passa nos testes das duas linhas do Spring Boot, com piso Java 17.

**História 10 — Documentação e versão:**

- Dado o repositório, quando se lê o `README.md` do componente, então ele descreve o contrato de uso do consumidor pelo hospedeiro (dependência, tabela, fila e DLQ, tratador, transação, configuração, métricas, limites e a limitação do bloqueio de chaves) com a mesma estrutura da seção da outbox.
- Dado o diretório `docs/adr/`, então existe o ADR `0002` com as decisões de desenho do consumidor e o índice o lista.
- Dados os POMs e os documentos do componente e do demo, então os artefatos `mensageria-outbox`, `mensageria-outbox-core`, `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4` estão em 0.4.0-SNAPSHOT.
- Dada a documentação autoritativa listada em "Impacto na documentação autoritativa" do [`plan.md`](./plan.md), então ela descreve o consumidor no `mensageria-outbox-core` e no mesmo contexto de beans.

## Comportamento atual → novo

| Aspecto | Hoje | Depois da unidade |
|---|---|---|
| Consumo | código próprio de cada aplicação (`basicGet` no demo) | `InboxConsumer` com `basicConsume`, `autoAck=false` e `basicQos` |
| Artefato do consumidor | não existe | o mesmo `mensageria-outbox-core`; sem artefato novo para importar |
| Envelope no consumo | headers lidos pela aplicação | `Envelope` de `br.com.db1.poc.shared.envelope` entregue ao tratador |
| Falha de processamento | mensagem volta à fila até o limite de entregas da fila quorum (20), quando o broker a descarta | retry com backoff na memória do consumidor e DLQ |
| Ordem por chave no consumo | sem serialização | uma mensagem por `aggregateKey` por vez |
| Spring | só beans da outbox | `InboxAutoConfiguration` com os beans do consumidor |
| Versão dos artefatos | 0.3.0-SNAPSHOT | 0.4.0-SNAPSHOT |

Permanecem iguais: o contrato da mensagem publicada, as regras R1 a R27 da outbox, as duas estratégias de relay, a `OutboxAutoConfiguration` e o piso Java 8 do `core` e Java 17 dos adaptadores.

## Dependências entre domínios

- **Unidade 004 (pacote `shared`)**: precondição; entrega `Envelope`, `Texts`, `PropertyReader`, `MessageMdc`, `RabbitTopology`, `SqlScripts` e `SqlServerErrors` em `br.com.db1.poc.shared` e o `PackageBoundaryTest` ampliado.
- **Contrato da mensagem publicada** ([`001/contracts/mensagem-publicada.md`](../001-mensageria-outbox-implementacao-modulo/contracts/mensagem-publicada.md)): headers, tipos e topologia lidos pelo consumidor; esta unidade os consome sem alterar.
- **Aplicação consumidora**: declara a fila principal (quorum, durável) e a DLQ no broker, cria a tabela `inbox` pelo script do dialeto, define o `InboxMessageHandler` e fornece o `ConnectionFactory` com a recuperação automática de conexão.
- **Demo `demooutboxinbox`**: continua com o consumo próprio até a sua P1.

## Riscos e observações

- **Bloqueio de chave só em memória (P7).** O reinício do consumidor libera as chaves bloqueadas e não há liberação durante a execução; a próxima mensagem de uma chave com mensagem na DLQ passa a ser aplicada sem aquela mensagem, o que quebra a ordem da chave. O limite está declarado no README do componente.
- **Mensagens em retry ocupam vagas em voo.** Uma chave em espera segura as suas mensagens sem ack, e elas contam em `max-in-flight`; uma chave que falha por muito tempo reduz a vazão das demais até a DLQ.
- **Reentrega depois de queda do canal.** O broker devolve à fila as mensagens sem ack do canal perdido e as copias em memória do consumidor ficam com a entrega inválida; a inbox descarta a reentrega, e um ack ou uma publicação na DLQ da cópia antiga pode falhar e ser repetido, com mensagem duplicada na DLQ no pior caso.
- **Mensagem sem `aggregateKey` legível.** Ela é serializada sob uma chave reservada e, depois de ir para a DLQ, bloqueia só essa chave reservada.
- **Piso Java 8.** O código do consumidor compartilha o piso do `core`; a skill `mensageria-inbox` e o `functional-map.md` citam Java 21 para o consumidor (ver "Impacto na documentação autoritativa" do plano).
- **Versão 0.4.0-SNAPSHOT não publicada.** O demo e os adaptadores só resolvem o artefato novo no repositório `JFrog-Sisprime` depois da publicação, ou no repositório Maven local depois de `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install`.
