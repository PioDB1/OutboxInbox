# Casos de teste: Mensageria Outbox — código comum da outbox e da inbox no pacote `shared`

Catálogo derivado de [`spec.md`](./spec.md) e [`plan.md`](./plan.md). O módulo é uma biblioteca sem endpoint; os casos observam a estrutura de pacotes, os imports, o build, o bytecode e a suíte existente da outbox. O comportamento do domínio não muda: os casos de funcionalidade (relay, outbox, lease, retenção, métricas) reaparecem apenas como a suíte existente rodando com os novos pacotes, sem recobrir as regras R1 a R27.

## Pré-condições

- Repositório na raiz, com o `pom.xml` raiz; o build roda em JDK 21 com Maven 3.9 ou superior. A suíte do `core` roda nos JDKs 8, 11, 17, 21 e 25 por `mvn -pl mensageria-outbox/core verify -Djvm=<JDK_HOME>/bin/java`.
- Casos "sem Docker": `*Test` (surefire). Casos "com Docker": `*IT` (failsafe, Testcontainers) com SQL Server, RabbitMQ e Toxiproxy, com credenciais efêmeras ou as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER` e `RABBITMQ_DEFAULT_PASS`; sem Docker em execução eles são ignorados e não contam como verificados.
- Os testes de comparação com o estado anterior usam uma cópia da revisão anterior à unidade, compilável e testável.

## História 1 — Pacote `shared`

### TC-1 (obrigatório) — Estrutura de pacotes do `shared` e remoção dos pacotes de origem

1. Listar os pacotes e as classes de `core/src/main/java/br/com/db1/poc`.

**Esperado:** existem `shared.envelope.Envelope`, `shared.text.Texts`, `shared.config.PropertyReader`, `shared.logging.MessageMdc`, `shared.rabbit.RabbitTopology`, `shared.schema.SqlScripts` e `shared.dialect.SqlServerErrors`; não existem `outbox.envelope`, `outbox.internal` nem `outbox.rabbit.RabbitTopology`.

### TC-2 (obrigatório) — `Envelope` preservado

1. Executar `EnvelopeTest` (movido para `shared.envelope`).

**Esperado:** passa sem alteração de asserção: validações de campo nulo, texto vazio e acima de 200, 100, 100 e 64 caracteres; cópia do payload na entrada e na saída; `equals`, `hashCode` e `toString`.

### TC-3 (obrigatório) — `Texts` preservado

1. Executar `TextsTest` (movido para `shared.text`).

**Esperado:** passa sem alteração de asserção, inclusive os espaços em branco não ASCII.

### TC-4 (obrigatório) — `PropertyReader` lê como o `RelayConfig`

1. Criar um `PropertyReader` com o prefixo `teste.` e propriedades com valores válidos: texto com espaços nas pontas, inteiro, decimal, duração em `ms`, `s`, `m`, `h`, `d` e ISO-8601 (`PT2S`).
2. Ler chaves ausentes com valor padrão.
3. Ler inteiro `abc`, decimal `x` e duração `5 parsecs`.

**Esperado:** valores válidos lidos com as unidades corretas e o texto sem as pontas; chaves ausentes devolvem o padrão; os três valores inválidos lançam `IllegalArgumentException` cuja mensagem cita a chave com o prefixo (`teste.<chave>`) e o valor, no mesmo formato de `RelayConfig`.

### TC-5 (obrigatório) — `MessageMdc` coloca e remove as chaves

1. Abrir `MessageMdc.open("m", "c", "a", "t")` em um `try` com recursos e ler o MDC dentro e depois do bloco.
2. Abrir com `messageId` nulo e `aggregateKey` `"a"` e ler o MDC.
3. Abrir com uma chave já presente no MDC com outro valor e fechar.

**Esperado:** dentro do bloco o MDC tem `messageId`, `correlationId`, `aggregateKey` e `traceId` com os valores passados; depois do bloco nenhuma das quatro chaves; com valor nulo a chave não aparece e as outras sim; `close` não lança exceção verificada.

### TC-6 (obrigatório) — `SqlScripts` divide o script em lotes

1. Ler um script de teste com linhas `GO`, `go`, `  Go  `, um lote em branco entre dois `GO` e um lote final sem `GO`.
2. Ler um recurso inexistente.

**Esperado:** a lista traz só os lotes não vazios, na ordem do arquivo, sem as linhas separadoras; o recurso inexistente lança `IllegalStateException` com o nome do recurso.

### TC-7 (obrigatório) — `SqlServerErrors.isDuplicateKey`

1. Chamar com `SQLException` de código 2627, 2601, 547 e 0.

**Esperado:** `true` para 2627 e 2601; `false` para 547 e 0.

### TC-8 (obrigatório) — `RabbitTopology` preservada (com Docker)

1. Declarar o exchange e a fila com `shared.rabbit.RabbitTopology` e inspecionar pelo broker.

**Esperado:** exchange `direct` durável; fila `quorum`, durável, não exclusiva e sem auto-exclusão, ligada à routing key, sem outros argumentos; declarar de novo com os mesmos argumentos conclui sem erro.

## História 2 — Comportamento da outbox preservado

### TC-9 (obrigatório) — Suíte `*Test` da outbox passa com os novos pacotes

1. Na raiz, com JDK 21, executar `mvn -pl mensageria-outbox/core test`.

**Esperado:** todos os `*Test` passam; nenhum teste teve asserção alterada, só `import` e `package`.

### TC-10 (obrigatório) — Suíte `*IT` da outbox passa com os novos pacotes (com Docker)

1. Executar `mvn -pl mensageria-outbox/core verify`.

**Esperado:** todos os `*IT` passam (publicação, ordem, backoff, lease, memória, observabilidade, retenção, esquema, escrita); nenhum é ignorado por outra razão que a falta de Docker.

### TC-11 (obrigatório) — Mensagens de erro do `RelayConfig` iguais

1. Em uma cópia da revisão anterior à unidade e na revisão atual, chamar `RelayConfig.fromProperties` com `outbox.relay.batch-size=abc`, `outbox.relay.backoff.multiplier=x`, `outbox.relay.poll-interval=5 parsecs` e sem `outbox.relay.strategy`.
2. Comparar as mensagens das exceções.

**Esperado:** as mensagens são idênticas nas duas revisões.

### TC-12 (obrigatório) — MDC do relay preservado

1. Executar `RelayObservabilityTest` (ou o `RelayObservabilityIT`) que captura os logs de uma tentativa de publicação.

**Esperado:** os eventos de log da tentativa trazem `messageId`, `correlationId`, `aggregateKey` e `traceId` da linha; o MDC fica sem as quatro chaves depois da tentativa.

### TC-13 (obrigatório) — Esquema instalado igual (com Docker)

1. Executar `OutboxSchemaInstaller.install` duas vezes sobre um SQL Server vazio (`OutboxSchemaInstallerIT`).

**Esperado:** as duas execuções concluem; as tabelas, índices e restrições são os mesmos da revisão anterior.

### TC-14 (obrigatório) — Bytecode Java 8 e matriz de JDKs

1. Executar `mvn -pl mensageria-outbox/core verify -Djvm=<JDK_HOME>/bin/java` em cada JDK 8, 11, 17, 21 e 25 (os `*IT` com Docker); executar `BytecodeLevelTest`.

**Esperado:** a suíte passa em cada JDK; todo `.class` de `target/classes` e de `target/test-classes` tem versão principal 52; o enforcer de bytecode passa.

## História 3 — Fronteira de pacotes verificada

### TC-15 (obrigatório) — `shared` não importa a outbox nem a inbox

1. Executar o `PackageBoundaryTest` sobre o código atual.
2. Acrescentar temporariamente a um fonte de `shared` o import `br.com.db1.poc.outbox.write.OutboxWriter`, executar o teste e reverter; repetir com um import de `br.com.db1.poc.inbox`.

**Esperado:** o passo 1 passa; no passo 2 o teste falha listando o arquivo e o import; depois de reverter passa de novo.

### TC-16 (obrigatório) — Outbox e inbox não dependem uma da outra

1. Acrescentar temporariamente a um fonte da outbox um import de `br.com.db1.poc.inbox` e a um fonte da inbox (quando existir) um import de `br.com.db1.poc.outbox`; executar o `PackageBoundaryTest` e reverter.

**Esperado:** o teste falha em cada caso listando arquivo e import; sem as alterações passa.

### TC-17 (obrigatório) — RabbitMQ só nos pacotes `rabbit` aceitos, Spring proibido

1. Acrescentar temporariamente `import com.rabbitmq.client.Channel;` a um fonte de `shared.envelope` e depois a um de `shared.rabbit`; acrescentar `import org.springframework.context.annotation.Bean;` a um fonte do `core`; executar o teste a cada vez e reverter.

**Esperado:** o teste falha para o primeiro e o terceiro casos e passa para o segundo; os diretórios aceitos são `shared/rabbit`, `outbox/rabbit` e `inbox/rabbit`.

## História 4 — Integração Spring

### TC-18 (obrigatório) — Código comum do Spring compila e passa nos dois adaptadores

1. Na raiz, executar `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify`.

**Esperado:** compila com `--release 17` e passa nos dois módulos, com `OutboxAutoConfigurationTest` e `OutboxDespachanteTest` em cada um, usando `Envelope` e `RabbitTopology` de `br.com.db1.poc.shared`.

## História 5 — Documentação

### TC-19 (recomendado) — Documentação com os pacotes novos

1. Procurar `outbox.envelope`, `outbox.internal`, `outbox/envelope` e `outbox.rabbit.RabbitTopology` nos documentos listados em "Impacto na documentação autoritativa" do [`plan.md`](./plan.md).

**Esperado:** nenhuma ocorrência; cada documento cita `br.com.db1.poc.shared.envelope.Envelope` e `br.com.db1.poc.shared.rabbit.RabbitTopology` onde os tipos aparecem, e o `AGENTS.md` descreve o pacote `shared` e a direção das dependências.
