# Spec: Mensageria Outbox — código comum da outbox e da inbox no pacote `shared`

## Visão geral

Extração, dos pacotes da outbox no `mensageria-outbox-core`, do código que a outbox e a inbox usam em comum para o pacote `br.com.db1.poc.shared`, com a outbox e o código comum do Spring ajustados aos novos pacotes e o comportamento da outbox preservado.

## Domínios envolvidos

- **Mensageria Outbox** — domínio alterado: tipos, pacotes, testes e documentação do `core`. Skill: [`.agents/skills/mensageria-outbox/SKILL.md`](../../skills/mensageria-outbox/SKILL.md) (regras R1 a R27; esta unidade não altera nenhuma delas).
- **Integração Spring Boot do componente** — o código comum `mensageria-outbox-spring-boot-shared` importa `Envelope` e `RabbitTopology` e passa a importá-los de `br.com.db1.poc.shared`. Skill: `../../../../mensageria-outbox-spring-boot-shared/.agents/skills/mensageria-outbox-spring-boot-shared/SKILL.md`.
- **Mensageria Inbox** — consome o pacote `shared`; a unidade [`003-mensageria-outbox-nucleo-inbox`](../003-mensageria-outbox-nucleo-inbox/spec.md) tem esta unidade como precondição. Skill: [`.agents/skills/mensageria-inbox/SKILL.md`](../../skills/mensageria-inbox/SKILL.md).

Origem: decisão do requisitante de 2026-10-21 de colocar o que é comum à inbox e à outbox em um pacote externo a ambas, chamado `shared`, e de especificar a correção da outbox; P8 de [`.agents/context/pendencias.md`](../../context/pendencias.md). Unidades anteriores: [`001-mensageria-outbox-implementacao-modulo`](../001-mensageria-outbox-implementacao-modulo/spec.md) e [`002-mensageria-outbox-compatibilidade-java-8`](../002-mensageria-outbox-compatibilidade-java-8/spec.md) (piso Java 8 do `core`).

## Estado atual do código

O `core` (`mensageria-outbox-core`, Java 8 como piso) tem os pacotes `envelope`, `dialect`, `relay`, `lease`, `publish`, `rabbit`, `retention`, `schema`, `metrics`, `write` e `internal` sob `br.com.db1.poc.outbox`. Neles vive código que a inbox, especificada na unidade 003, usa da mesma forma:

| Código | Onde está hoje | Quem o usa |
|---|---|---|
| `Envelope` | `outbox.envelope` | outbox (`write`, `dialect`, `publish`, `relay`), código comum do Spring, demo; a inbox lê as mensagens para ele |
| `Texts` | `outbox.internal` | `RelayConfig`, `OutboxSchemaInstaller` e a configuração da inbox |
| Leitura de propriedades (texto, inteiro, decimal, duração) | métodos privados de `outbox.relay.RelayConfig` | `RelayConfig`; a configuração da inbox lê o mesmo formato de chaves |
| MDC `messageId`, `correlationId`, `aggregateKey`, `traceId` | `outbox.relay.RelayRunner.publish` | relay; o consumidor da inbox o aplica em cada mensagem |
| `RabbitTopology` | `outbox.rabbit` | publicador, código comum do Spring, demo; a inbox declara a DLQ com a mesma topologia |
| Leitura do script SQL em lotes separados por `GO` | métodos privados de `outbox.schema.OutboxSchemaInstaller` | `OutboxSchemaInstaller`; o instalador da inbox lê o mesmo formato |
| Reconhecimento de violação de chave do SQL Server (códigos 2627 e 2601) | método privado de `outbox.dialect.sqlserver.SqlServerOutboxDialect` | dialeto da outbox e dialeto SQL Server da inbox |
| Suporte de teste genérico (`Await`, `HeapProbe`, `LogCapture`, `Images`, `ComposeImagesTest`, `TestInfrastructure`, `TestEnvelopes`) | `outbox.support` (teste) | testes da outbox e da inbox |

`SqlServerIT`, `BrokerIT`, `OutboxDb`, `RelayIT` e `ScriptedPublisher` dependem de tipos da outbox e permanecem em `outbox.support`. O `PackageBoundaryTest` aceita `com.rabbitmq` só em `br/com/db1/poc/outbox/rabbit` e proíbe Spring em todo o `src/main`. `BackoffPolicy` (estado por chave, usado só pela thread do relay) e as métricas são específicos da outbox.

## Escopo

**Dentro:**

- Um pacote `shared`, externo à outbox e à inbox, com o código que as duas usam, disponível no mesmo artefato `mensageria-outbox-core`.
- A outbox, o código comum do Spring e os testes usando o pacote `shared`, com o mesmo comportamento de publicação, relay, lease, retenção, métricas, logs e esquema.
- Uma verificação de build que impede o `shared` de depender da outbox ou da inbox e a outbox e a inbox de dependerem uma da outra.
- A documentação do componente com os novos pacotes dos tipos movidos.

**Fora:**

- Qualquer mudança de regra de negócio, de contrato da mensagem, de esquema SQL ou de comportamento do relay.
- Correção dos defeitos P1 a P5 de `pendencias.md`, inclusive o `backoff.multiplier=NaN` (P3): `BackoffPolicy` e a validação de `RelayConfig` permanecem como estão, e por isso o backoff não vai para o `shared`.
- O código do consumidor (unidade 003) e a auto-configuração Spring do consumidor.
- Ajuste do `demooutboxinbox` aos novos pacotes: P2 de `../../../../demooutboxinbox/.agents/context/pendencias.md`.
- Novo artefato Maven ou módulo para o `shared`.

## Fronteira do domínio

**Esta spec implementa:**

- Pacote `br.com.db1.poc.shared.envelope` com `Envelope`.
- Pacote `br.com.db1.poc.shared.text` com `Texts`.
- Pacote `br.com.db1.poc.shared.config` com `PropertyReader`, extraído dos métodos privados de leitura de `RelayConfig`.
- Pacote `br.com.db1.poc.shared.logging` com `MessageMdc`, extraído de `RelayRunner.publish`.
- Pacote `br.com.db1.poc.shared.rabbit` com `RabbitTopology`.
- Pacote `br.com.db1.poc.shared.schema` com `SqlScripts`, extraído de `OutboxSchemaInstaller`.
- Pacote `br.com.db1.poc.shared.dialect` com `SqlServerErrors`, extraído de `SqlServerOutboxDialect`.
- Pacote de teste `br.com.db1.poc.shared.support` com `Await`, `HeapProbe`, `LogCapture`, `Images`, `ComposeImagesTest`, `TestInfrastructure` e `TestEnvelopes`, e os testes `EnvelopeTest` e `TextsTest` movidos para os pacotes de teste de `shared`.
- Testes novos `PropertyReaderTest`, `MessageMdcTest`, `SqlScriptsTest` e `SqlServerErrorsTest`.
- Regras novas no `PackageBoundaryTest`.
- Alteração das classes da outbox (`write`, `dialect`, `publish`, `relay`, `schema`, `rabbit`, `retention`, `lease`, `metrics`) e do código comum do Spring (`OutboxAutoConfiguration`, `OutboxDespachante`, `OutboxGravada`, `OutboxGravador`, `OutboxRecuperacao` e os testes) para os novos imports.

**Pertence a outros domínios (dependência entre domínios, não vira tarefa aqui):**

- Código do consumidor e a sua configuração, MDC, dialetos e instalador de esquema que usam o `shared` → unidade 003 (domínio Mensageria Inbox).
- Imports do `demooutboxinbox` (`Envelope`, `RabbitTopology`) → P2 do demo.
- Auto-configuração Spring do consumidor → unidade 003.

## Histórias de usuário

1. Como mantenedor do componente, quero o código comum à outbox e à inbox em um pacote `shared` independente de ambas, para que nenhum lado dependa do outro.
2. Como equipe de uma aplicação produtora, quero que a outbox se comporte como antes da extração, para que a mudança de pacotes não altere a garantia de entrega.
3. Como mantenedor, quero que o build verifique a direção das dependências entre `shared`, `outbox` e `inbox`, para que a independência não se perca em mudanças futuras.
4. Como equipe da integração Spring, quero que o código comum e os dois adaptadores compilem e passem nos testes com os novos pacotes, para manter as duas linhas do Spring Boot funcionando.
5. Como mantenedor, quero a documentação com os pacotes novos dos tipos movidos, para que a API pública seja a descrita.

## Critérios de aceitação

**História 1 — Pacote `shared`:**

- Dado o `core`, quando se listam os pacotes de `src/main/java`, então existem `br.com.db1.poc.shared.envelope`, `.text`, `.config`, `.logging`, `.rabbit`, `.schema` e `.dialect` com as classes da seção "Fronteira do domínio", e `outbox.envelope`, `outbox.internal` e `outbox.rabbit.RabbitTopology` deixam de existir.
- Dado o código do `shared`, quando se leem os imports, então nenhum fonte importa `br.com.db1.poc.outbox` nem `br.com.db1.poc.inbox`.
- `Envelope` mantém campos, validações, tamanhos máximos, `equals`, `hashCode`, `toString` e a cópia do payload; `Texts` mantém a definição de espaço em branco de `Character.isWhitespace(int)`; `RabbitTopology` mantém `declareExchange` (exchange `direct` durável) e `declareQuorumQueue(Channel, String, String, String)` (fila `quorum` durável e ligação à routing key) com os argumentos de fila inalterados.
- `PropertyReader` lê texto, inteiro, decimal e duração com as mesmas regras, valores padrão e mensagens de erro que os métodos de `RelayConfig` (prefixo da chave na mensagem, durações `ms`, `s`, `m`, `h`, `d` ou ISO-8601); `SqlScripts` devolve os lotes do script separados por linhas `GO` (sem diferenciar maiúsculas de minúsculas), descartando lotes em branco; `SqlServerErrors.isDuplicateKey` devolve `true` para os códigos 2627 e 2601 e `false` para os demais; `MessageMdc` coloca `messageId`, `correlationId`, `aggregateKey` e `traceId` no MDC, ignora valor nulo e remove do MDC, ao fechar, as chaves que colocou.

**História 2 — Comportamento da outbox preservado:**

- Dados os testes `*Test` e `*IT` existentes da outbox, quando se ajustam só os imports e os pacotes dos tipos movidos, então todos passam, nos JDKs 8, 11, 17, 21 e 25 (os `*IT` com Docker), sem alteração de asserção.
- Dado o `RelayConfig`, quando uma chave tem valor inválido, então a mensagem de erro é a mesma de antes da extração.
- Dado o `OutboxSchemaInstaller`, quando é executado sobre um SQL Server vazio e de novo sobre o mesmo banco, então o esquema criado e o comportamento idempotente são os mesmos.
- Dada uma tentativa de publicação do relay, quando o logger escreve, então o MDC traz `messageId`, `correlationId`, `aggregateKey` e `traceId` e é limpo ao final, como antes.
- Dado o JAR do `core`, então todo `.class` tem versão principal 52 (Java 8), e o enforcer de bytecode continua passando.

**História 3 — Fronteira de pacotes verificada pelo build:**

- Dado o `PackageBoundaryTest`, quando um fonte do `shared` importa `br.com.db1.poc.outbox` ou `br.com.db1.poc.inbox`, ou um fonte da outbox importa `br.com.db1.poc.inbox`, ou um fonte da inbox importa `br.com.db1.poc.outbox`, então o teste falha e lista o arquivo e o import.
- O teste aceita `com.rabbitmq` somente nos diretórios `br/com/db1/poc/shared/rabbit`, `br/com/db1/poc/outbox/rabbit` e `br/com/db1/poc/inbox/rabbit`, e proíbe `org.springframework` em todo o `src/main`.

**História 4 — Integração Spring:**

- Dado o código comum do Spring com os novos imports, quando se executa `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify`, então os dois módulos compilam com `--release 17` e passam nos testes do código comum.

**História 5 — Documentação:**

- Dado o repositório, quando se leem o `README.md` do componente, os READMEs dos adaptadores, a skill do código comum do Spring, o `AGENTS.md` e os contratos `api-modulo.md` e `mensagem-publicada.md` da unidade 001, então eles citam os pacotes novos de `Envelope` e `RabbitTopology` e descrevem o pacote `shared`.

## Comportamento atual → novo

| Aspecto | Hoje | Depois da unidade |
|---|---|---|
| `Envelope` | `br.com.db1.poc.outbox.envelope` | `br.com.db1.poc.shared.envelope` |
| `RabbitTopology` | `br.com.db1.poc.outbox.rabbit` | `br.com.db1.poc.shared.rabbit` |
| `Texts` | `br.com.db1.poc.outbox.internal` | `br.com.db1.poc.shared.text` |
| Leitura de propriedades, MDC, leitura de script SQL, reconhecimento de violação de chave | métodos privados da outbox | classes públicas do `shared` |
| Suporte de teste genérico | `outbox.support` | `shared.support` |
| `PackageBoundaryTest` | RabbitMQ só em `outbox/rabbit`; Spring proibido | também verifica a direção das dependências entre `shared`, `outbox` e `inbox` |

Permanecem iguais: o comportamento da outbox e do relay, o contrato da mensagem publicada, o esquema SQL, as chaves `outbox.relay.*`, os nomes de classe, o artefato `mensageria-outbox-core` e o piso Java 8.

## Dependências entre domínios

- **Unidade 003** (consumidor): precondição dela; usa `shared.envelope`, `shared.text`, `shared.config`, `shared.logging`, `shared.rabbit`, `shared.schema` e `shared.dialect`.
- **`demooutboxinbox`**: importa `Envelope` e `RabbitTopology`; não compila com os pacotes novos até a P2 do demo.
- **Aplicações hospedeiras**: quem importa `Envelope` ou `RabbitTopology` do `mensageria-outbox-core` ajusta os imports ao subir para 0.4.0-SNAPSHOT.

## Riscos e observações

- **Quebra de API.** Os pacotes de `Envelope` e `RabbitTopology` mudam na versão 0.4.0-SNAPSHOT; quem os importa precisa ajustar os imports (o demo, na P2 dele). Nenhum nome de classe muda.
- **`shared` no mesmo artefato.** O pacote não é um artefato separado; a independência entre os lados é verificada pelo `PackageBoundaryTest`, e não por fronteira de módulo Maven.
- **Código de relay tocado.** `RelayConfig` e `RelayRunner` passam a delegar para `PropertyReader` e `MessageMdc`; a regressão é coberta pelos testes existentes do relay.
- **P3 preservada.** A leitura de `backoff.multiplier` continua aceitando `NaN` na outbox; a extração não altera a validação.
