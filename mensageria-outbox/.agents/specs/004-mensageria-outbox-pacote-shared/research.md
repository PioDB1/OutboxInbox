# Pesquisa: Mensageria Outbox — código comum da outbox e da inbox no pacote `shared`

Contexto, alternativas e evidências das decisões do [`plan.md`](./plan.md).

## Onde fica o `shared`

**Contexto.** O requisitante pediu "um pacote externo chamado shared" para o que é comum à inbox e à outbox, depois de decidir que a inbox fica no `mensageria-outbox-core` e no pacote `br.com.db1.poc.inbox`, sem artefato novo para importação.

**Alternativas:**

- **Pacotes `br.com.db1.poc.shared.*` no `core`** — externos à outbox e à inbox; mesmo artefato e mesmo piso Java 8.
- **Módulo Maven `mensageria-shared`** — um artefato novo que o `core` e os adaptadores passariam a importar; contraria a decisão de não criar artefato para importação.

**Decisão.** Pacotes `br.com.db1.poc.shared.*` no `core`.

**Evidência.** `core/pom.xml` (artefato único `mensageria-outbox-core`); decisão do requisitante de 2026-10-21 (sem novo artefato para importação); `mensageria-outbox/pom.xml` (piso Java 8 e enforcer valem para todo o módulo).

**Consequências.** A independência entre outbox e inbox é uma regra de pacotes, verificada pelo `PackageBoundaryTest`, e não uma fronteira de módulo Maven.

## O que é comum

**Contexto.** A inbox da unidade 003 usa, com o mesmo comportamento, código que hoje está na outbox.

**Evidência (leitura do código):**

- `Envelope`: a inbox lê as mensagens para o mesmo tipo (decisão da unidade 003).
- `Texts`: público, já usado de `relay` e de `schema`; a configuração e o instalador da inbox precisam das mesmas operações.
- Leitura de propriedades: `RelayConfig.text`, `integer`, `decimal` e `duration` (formato `ms`, `s`, `m`, `h`, `d` ou ISO-8601, mensagens com o prefixo da chave); `InboxConfig.fromProperties` lê o mesmo formato.
- MDC: `RelayRunner.publish` abre `messageId`, `correlationId`, `aggregateKey` e `traceId` com `MDC.putCloseable`; o consumidor aplica as mesmas quatro chaves (R16 da skill `mensageria-inbox`).
- `RabbitTopology`: os argumentos da fila quorum precisam ser idênticos em todo declarante ("uma declaração com argumentos diferentes recebe `PRECONDITION_FAILED`", Javadoc da classe); a DLQ da inbox usa a mesma classe.
- Leitura de script em lotes `GO`: `OutboxSchemaInstaller.readBatches`; o script da inbox usa o mesmo separador.
- `SqlServerOutboxDialect.isDuplicateKey`: códigos 2627 e 2601; o dialeto SQL Server da inbox reconhece a mesma violação.

**Alternativas descartadas:**

- **`BackoffPolicy` e o cálculo do backoff** — a classe guarda estado por chave e é usada só pela thread do relay; extrair o cálculo e a validação exigiria decidir o tratamento do `backoff.multiplier=NaN`, que é a P3 registrada sem correção (D15). O consumidor da inbox tem a própria política, com a validação que recusa `NaN`.
- **Métricas** — `OutboxMetrics` e `InboxMetrics` têm medidores diferentes; só o padrão é comum.
- **Interface de dialeto** — o SQL da outbox e o da inbox não têm operação em comum.
- **`SqlServerIT`, `BrokerIT`, `OutboxDb`, `RelayIT`, `ScriptedPublisher`** — dependem de `OutboxSchemaInstaller`, `OutboxDialect`, `MessagePublisher` ou `RelayFactory`; ficam em `outbox.support`. A inbox escreve as próprias bases de teste sobre o `TestInfrastructure` do `shared`.

**Decisão.** Entram no `shared` as sete classes de `main` e as sete de suporte de teste listadas na spec.

## Forma das classes extraídas

**Decisão.** Cada extração preserva o comportamento atual do código de origem:

- `PropertyReader` conserva os padrões e as mensagens de `RelayConfig`, com o prefixo vindo do construtor; `RelayConfig` passa a delegar a ele e continua com a validação própria (incluindo a que aceita `NaN`).
- `MessageMdc.open` aceita valor nulo sem colocá-lo no MDC (o consumidor trata mensagem de envelope ilegível); o relay sempre passa valores não nulos, e o resultado para ele é o mesmo.
- `SqlScripts.readBatches(Class<?> anchor, String resource)` conserva a divisão por linha `GO` sem diferenciar maiúsculas de minúsculas e o descarte de lote em branco.
- `SqlServerErrors.isDuplicateKey` conserva os dois códigos.

**Evidência.** Código atual de `RelayConfig` (linhas das funções privadas), `RelayRunner.publish`, `OutboxSchemaInstaller.readBatches`, `SqlServerOutboxDialect.isDuplicateKey`; os testes existentes (`RelayConfigTest`, `OutboxSchemaInstallerIT`, `RelayObservabilityTest`, `RelayAlgorithmTest`) cobrem o comportamento delegado.

## Fronteira de pacotes verificada

**Contexto.** O `PackageBoundaryTest` verifica Spring e RabbitMQ por import em `src/main/java`.

**Decisão.** O teste ganha regras de direção por diretório de origem: `br/com/db1/poc/shared` não importa `br.com.db1.poc.outbox` nem `br.com.db1.poc.inbox`; `br/com/db1/poc/outbox` não importa `br.com.db1.poc.inbox`; `br/com/db1/poc/inbox` não importa `br.com.db1.poc.outbox`; `com.rabbitmq` só é aceito em `shared/rabbit`, `outbox/rabbit` e `inbox/rabbit`.

**Evidência.** `PackageBoundaryTest.java` (percurso por `Files.walk`, `importsOf`, `RABBIT_PACKAGE`).

**Consequências.** Com a regra de que a inbox não importa a outbox, a inbox usa tudo o que é comum pelo `shared`; a unidade 003 depende desta unidade.
