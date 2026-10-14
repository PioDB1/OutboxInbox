# Spec: Mensageria Outbox — implementação do módulo produtor

## Visão geral

Implementação do módulo `mensageria-outbox`, o lado produtor da entrega atômica de mensagens: envelope padrão, inserção na outbox dentro da transação local da aplicação, relay com duas estratégias atrás de uma única interface, lease de relay único, publicação com publisher confirms, retry com backoff, retenção da outbox, métricas de publicação e propagação de `correlationId`/`traceId`.

## Domínio

- Slug: `mensageria-outbox`
- Skill: [`.agents/skills/mensageria-outbox/SKILL.md`](../../skills/mensageria-outbox/SKILL.md) (regras R1 a R27 citadas abaixo)
- Dependências técnicas do domínio: [`.agents/skills/mensageria-outbox/references/technical-dependencies.md`](../../skills/mensageria-outbox/references/technical-dependencies.md)

## Estado atual do código

O repositório não contém código-fonte, `pom.xml`, `compose.yaml` nem `docs/adr/`: apenas o material de descoberta em `.agents/`, o spec de entrada e arquivos herdados de template (`README.md`, `LICENSE`, `.gitignore`, `.github/workflows/gradle.yml`). Esta é a primeira materialização do domínio e do build Maven multi-módulo; a spec cobre o domínio inteiro e a infraestrutura transversal que ele é o primeiro a exigir (ver "Fronteira do domínio").

A forma de entrega é a **biblioteca Java 21 sem framework de aplicação**, embutida pela aplicação hospedeira (Pagamentos), conforme `AGENTS.md` (módulo `mensageria-outbox` "sem Spring Boot") e a decisão D3 (Pagamentos consome o módulo "por adaptador fino"). O relay é um componente com ciclo de vida (`start`/`stop`) iniciado pelo hospedeiro; não há executável próprio nem endpoint HTTP no módulo.

## Escopo

**Dentro:**

- Envelope padrão da mensagem, preenchido e propagado pelo módulo.
- Gravação da mensagem na outbox na mesma transação local do dado de negócio da aplicação produtora.
- Publicação das mensagens pendentes no RabbitMQ com confirmação do broker, at-least-once.
- Ordem de publicação preservada por `aggregateKey`, sem pular mensagens quando transações confirmam fora da ordem de `seq`.
- Duas estratégias de relay (claim e ponto de leitura), selecionáveis por configuração, para comparação.
- Retry com backoff nas falhas de publicação, com acúmulo na outbox enquanto o broker estiver indisponível.
- No máximo um relay ativo por banco de origem, com troca automática se o ativo morrer, e relay habilitado somente em banco primário/ativo.
- Retenção/limpeza configurável da outbox.
- Métricas de publicação e logs estruturados com propagação de `correlationId` e `traceId`.
- Reinício do relay a qualquer momento sem intervenção manual e consumo de memória limitado.
- ADR da estratégia de relay, com as observações coletadas pelo módulo.
- Ambiente local e de testes reproduzível (SQL Server, RabbitMQ, Toxiproxy).

**Fora:**

- Dados de negócio de Pagamentos, endpoint de solicitação de pagamento e conteúdo/validação do payload — domínio Pagamentos.
- Adaptador de Spring Boot para o módulo — domínio Pagamentos (decisão D3).
- Consumo, inbox, dedup, retry de consumo e DLQ — domínio `mensageria-inbox`.
- Verificação independente das invariantes A1 a A4 e suíte de testes de falha A-F1 a A-F7 — domínio Reconciliação.
- Decisão final entre as estratégias de relay — decisão humana, registrada em ADR (status `Proposto` nesta unidade).
- Caso B (coordenação de vários passos com compensação), CDC/Debezium, ponte ActiveMQ ↔ RabbitMQ, replicação de bancos e qualquer meta de latência ou throughput.

## Fronteira do domínio

**Esta spec implementa:**

- Módulo Maven `mensageria-outbox` (Java 21, sem dependência de Spring Boot) e o `pom.xml` raiz multi-módulo que o agrega.
- Tipo `Envelope` com os campos `messageId`, `correlationId`, `idempotencyKey`, `aggregateKey`, `type`, `schemaVersion`, `occurredAt` e `payload`, e o metadado de propagação `traceId`.
- Componente de inserção na outbox, que usa a conexão e a transação local abertas pela aplicação.
- Tabela `outbox` (SQL Server) com as colunas do esboço da skill acrescidas das colunas de propagação (`correlation_id`, `idempotency_key`, `occurred_at`, `trace_id`), de `rowversion` e do necessário para a estratégia claim marcar a linha como enviada; tabela de lease; tabela de ponto de leitura; scripts de criação de esquema das três.
- Interface `Relay` e suas duas implementações: estratégia claim (`UPDLOCK`/`READPAST`/`ROWLOCK`) e estratégia de ponto de leitura (`rowversion` com `min_active_rowversion()`).
- Componente de lease de relay único, com expiração e renovação, guardado no banco de origem.
- Interface de publicação (porta do broker) e adaptador RabbitMQ com publisher confirms, filas quorum duráveis e declaração de topologia acionada pela aplicação.
- Política de backoff de publicação e rotina de retenção/limpeza da outbox (em lote na estratégia de ponto de leitura).
- Isolamento do acesso a banco por dialeto, com o dialeto SQL Server como único implementado.
- Métricas Micrometer `outbox.lag.seconds`, `outbox.pending` e `outbox.publish.retries`, e consulta somente leitura das linhas pendentes por estratégia.
- Configuração do módulo: relay habilitado, estratégia, backoff, retenção, duração e renovação do lease, tamanho de lote.
- Convenção de log estruturado (`messageId`, `correlationId`, `aggregateKey`) e propagação de `traceId` aplicada ao relay.
- Testes do módulo com Testcontainers (SQL Server, RabbitMQ) e Toxiproxy.
- `compose.yaml`, `.env.example` e as entradas de `.gitignore` para `.env` e `target/` (skill `ambiente-docker-sqlserver-rabbitmq`).
- `docs/adr/README.md` e `docs/adr/0001-estrategia-de-relay.md` com status `Proposto` (skill `registro-de-adrs`).

**Pertence a outros domínios (dependência entre domínios, não vira tarefa aqui):**

- Tabela `pagamento`, endpoint de solicitação, evento `PagamentoRecebido` v1 e adaptador de Spring Boot → skill `pagamentos` (a ser criada na unidade do domínio).
- Envelope do lado consumidor, inbox, dedup, ack manual, retry de consumo, DLQ e métricas de consumo → skill `mensageria-inbox`.
- Job de reconciliação e classes `FalhaAF1Test` a `FalhaAF7Test` → domínio Reconciliação.
- Tabelas e consumidor de Extrato → domínio Extrato.

## Histórias de usuário

1. Como equipe da aplicação produtora, quero gravar o dado de negócio e a mensagem na mesma transação local, para que a mensagem exista se, e somente se, o dado existir.
2. Como equipe da aplicação produtora, quero que cada mensagem pendente seja publicada no RabbitMQ somente após o confirm do broker, para não perder mensagens (at-least-once).
3. Como equipe da aplicação produtora, quero que mensagens do mesmo `aggregateKey` sejam publicadas na ordem em que ocorreram e que nenhuma seja pulada quando transações confirmam fora da ordem de `seq`, para preservar a consistência por agregado nas duas estratégias de relay.
4. Como operador, quero que falhas de publicação sigam retry com backoff e que as mensagens acumulem na outbox enquanto o broker estiver fora, para que todas sejam publicadas na volta, sem tempestade de tentativas.
5. Como operador, quero no máximo um relay ativo por banco de origem, que só rode em banco primário/ativo e que outro assuma se o ativo morrer, para reiniciar sem intervenção manual e sem perda mesmo com dois relays simultâneos.
6. Como operador, quero uma política de retenção configurável, para que a outbox não cresça sem limite sem afetar a ordem nem a segurança do relay.
7. Como operador, quero métricas de publicação e logs estruturados com `correlationId` e `traceId`, para observar lag, pendentes e retries e rastrear uma mensagem de ponta a ponta.
8. Como mantenedor da PoC, quero a comparação entre as duas estratégias registrada em um ADR, para que a pessoa decisora escolha com dados.
9. Como mantenedor da PoC, quero um ambiente Docker e de testes reproduzível, para repetir os cenários de falha do lado produtor.

## Critérios de aceitação

**História 1 — Inserção transacional (R1 a R8):**

- Dado uma transação local aberta pela aplicação, quando a aplicação grava seu dado de negócio e insere um envelope pelo módulo e faz commit, então existe exatamente uma linha em `outbox` com `message_id`, `aggregate_key`, `type`, `schema_ver`, `payload`, `correlation_id`, `idempotency_key`, `occurred_at`, `trace_id`, `seq` crescente e `created_at` preenchidos.
- Dado a mesma transação, quando ela sofre rollback depois da inserção, então não existe linha em `outbox` nem dado de negócio.
- Dado um envelope com `occurredAt` informado, quando a linha é persistida e depois publicada, então `occurredAt` mantém o instante informado em ISO-8601 UTC, distinto do `created_at`.
- Dado um payload binário qualquer, quando é inserido e publicado, então os bytes publicados são idênticos aos inseridos (o módulo não interpreta nem valida o payload).
- Dado que o módulo não abre transação própria, quando a inserção é chamada sem transação aberta pela aplicação, então a chamada falha com erro explícito e nenhuma linha é gravada.

**História 2 — Publicação com confirms (R9, R10, R21, R22):**

- Dado uma linha pendente e o broker disponível, quando o relay a publica, então a mensagem chega à fila quorum durável com os campos do envelope e os headers `messageId`, `correlationId` e `traceId`, e a linha só passa a `enviada` depois do confirm positivo.
- Dado um confirm negativo, ausência de confirm dentro do timeout ou erro de conexão, quando o relay tenta publicar, então a linha continua pendente.
- Dado que o relay é interrompido depois do confirm positivo e antes de registrar o envio, quando ele reinicia, então publica a mesma mensagem novamente (`messageId` idêntico).
- Dado o código-fonte do módulo, então o pacote do núcleo do relay não importa `org.springframework` nem `com.rabbitmq`; somente o adaptador RabbitMQ importa `com.rabbitmq`, e uma implementação alternativa da interface de publicação em teste substitui o broker sem alterar o núcleo.

**História 3 — Ordem e nenhuma linha pulada (R7, R11, R15 a R17):**

- Dado duas mensagens do mesmo `aggregateKey` inseridas em sequência, quando o relay publica, então a mais antiga é publicada antes da mais nova nas duas estratégias.
- Dado uma mensagem de um `aggregateKey` com falha de publicação e aguardando nova tentativa, quando há uma mensagem mais nova do mesmo `aggregateKey`, então esta não é publicada antes da anterior; mensagens de outros `aggregateKey` seguem sendo publicadas.
- Dado a transação T1 que insere na outbox (obtendo `seq` menor) e permanece aberta, e a transação T2 que insere (obtendo `seq` maior) e confirma antes de T1, quando o relay executa ciclos e só então T1 confirma, então a mensagem de T1 é publicada em ambas as estratégias e nenhuma mensagem é pulada.
- Dado a configuração da estratégia de relay (claim ou ponto de leitura), quando o módulo inicia, então apenas a estratégia escolhida roda; configuração ausente ou com valor desconhecido faz a inicialização falhar com mensagem que cita os valores aceitos.
- Dado a versão fixada da imagem do SQL Server, então a semântica de `min_active_rowversion()` observada em teste (limite seguro com transação aberta e depois confirmada) está registrada no ADR `0001`, e o teste que a verifica está na suíte do módulo.

**História 4 — Retry com backoff e acúmulo (R12, R13):**

- Dado o broker indisponível por um período durante o qual a aplicação segue inserindo N mensagens, quando o broker volta, então as N mensagens são publicadas e nenhuma linha é descartada.
- Dado falhas sucessivas de publicação da mesma linha, quando o relay tenta novamente, então o intervalo entre tentativas é não decrescente, respeita o teto configurado e o número de tentativas no período observado fica abaixo do que ocorreria sem backoff (sem tentativas em laço apertado).
- Dado o broker disponível, então nenhuma linha pendente permanece sem publicação além do intervalo de backoff vigente.

**História 5 — Lease, instância única e primário (R14, R18, R19, R19a):**

- Dado dois relays configurados sobre o mesmo banco de origem, quando ambos iniciam, então somente o detentor do lease publica.
- Dado o relay ativo encerrado sem liberar o lease, quando o lease expira, então o outro relay assume e publica as linhas pendentes sem intervenção manual.
- Dado dois relays ativos ao mesmo tempo por sobreposição forçada do lease (expiração curta e instância antiga congelada), quando ambos publicam, então nenhuma mensagem é perdida e as duplicidades têm o mesmo `messageId`.
- Dado a configuração "relay habilitado" desligada, quando o hospedeiro inicia, então o relay não adquire o lease nem publica.
- Dado o relay interrompido a qualquer momento (inclusive no meio de um lote) e reiniciado, quando retoma, então nenhum estado em memória é necessário: linhas pendentes, lease e ponto de leitura vêm do banco.

**História 6 — Retenção (R23):**

- Dado linhas enviadas com confirm além do prazo de retenção configurado, quando a limpeza executa, então somente essas linhas são removidas.
- Dado linhas pendentes (inclusive as de falha em retry), então a limpeza não as remove, qualquer que seja a idade.
- Dado a estratégia de ponto de leitura, quando a limpeza executa, então ela remove em lote e não remove linhas ainda necessárias para calcular o limite seguro ou posteriores ao ponto de leitura persistido; o relay continua sem pular mensagens depois da limpeza.

**História 7 — Observabilidade (R24, R25):**

- Dado um `MeterRegistry` Micrometer, quando o relay executa, então estão registrados `outbox.lag.seconds` (idade em segundos da linha pendente mais antiga, 0 sem pendentes), `outbox.pending` (quantidade de linhas pendentes) e `outbox.publish.retries` (contador de tentativas de retry de publicação).
- Dado o broker indisponível com mensagens acumuladas, então `outbox.pending` e `outbox.lag.seconds` crescem e, após a volta e a publicação de todas, retornam a 0.
- Dado uma mensagem inserida com `correlationId` e `traceId`, quando publicada, então ambos aparecem como headers da mensagem e nos logs do relay; todo log do relay sobre uma mensagem traz `messageId`, `correlationId` e `aggregateKey` como campos estruturados.
- Dado que nenhuma métrica tem valor-meta, então nenhum teste afirma limite de latência ou de throughput.

**História 8 — ADR (R16):**

- Dado a conclusão da implementação, então existem `docs/adr/README.md` (com uma linha por ADR: número, título, status, data) e `docs/adr/0001-estrategia-de-relay.md` no formato da skill `registro-de-adrs`, com status `Proposto` e a seção "Observações da PoC" preenchida com dados medidos do módulo: resultado do cenário de commits fora da ordem de `seq`, lag, pendentes, tentativas de retry, tempo de recuperação, contenção observada, linhas de código e quantidade de testes de cada estratégia.

**História 9 — Ambiente e build (dependências técnicas):**

- Dado Docker em execução e um `.env` criado a partir de `.env.example`, quando se executa `docker compose up -d --wait`, então `sqlserver`, `rabbitmq` e `toxiproxy` ficam saudáveis, com tags de imagem fixas (nenhuma `latest`).
- Dado `mvn clean verify` na raiz, então o módulo `mensageria-outbox` compila em Java 21 e seus testes de integração (Testcontainers, com contêineres próprios e credenciais efêmeras) passam.
- Dado o repositório, então nenhuma credencial, senha ou string de conexão com segredo está versionada, `.env` e `target/` constam do `.gitignore`, e as credenciais chegam ao módulo por variáveis de ambiente ou propriedades externas.
- Dado o teste de carga do relay com um volume que excede várias vezes o tamanho do lote, executado com limites explícitos de heap e de memória direta no `argLine` do teste, então o processo termina sem `OutOfMemoryError` e o consumo de memória do relay não cresce com o número de linhas processadas (leitura em lotes limitados).

## Dependências técnicas do domínio (primeira materialização)

| Dependência | Situação | Tratamento |
|---|---|---|
| Ambiente Docker com SQL Server, RabbitMQ e Toxiproxy | inexistente | criada nesta spec (`compose.yaml`, `.env.example`) |
| Testcontainers e Toxiproxy | inexistentes | dependências de teste do módulo; testes do módulo cobrem os comportamentos do lado produtor |
| ADRs | inexistentes | criados nesta spec (índice e `0001`, `Proposto`) |
| Convenção de logs estruturados | inexistente | aplicada ao relay nesta spec |
| Driver JDBC do SQL Server | inexistente | dependência do módulo |
| Scripts de esquema (outbox, lease, ponto de leitura) | inexistentes | criados nesta spec |
| Limites de heap e de memória direta do relay | inexistentes | aplicados e verificados no teste de carga; o hospedeiro define os do processo em produção |
| Credenciais por variável de ambiente | inexistentes | critério da história 9 |
| Contexto de rastreamento com `traceId` na origem | pertence ao hospedeiro | o módulo recebe o `traceId` informado pela aplicação na inserção e o persiste; a origem do valor é dependência do domínio Pagamentos |

## Dependências entre domínios

- **Pagamentos** — hospedeiro do módulo: chama a inserção dentro da sua transação local, informa `correlationId`, `idempotencyKey`, `aggregateKey` e `traceId`, inicia o relay, aciona a declaração de topologia, escolhe os limites do processo e fornece o adaptador de Spring Boot.
- **Mensageria Inbox / Extrato** — consumidor das mensagens publicadas: absorve as duplicidades do at-least-once pelo `messageId`; a fila quorum e a DLQ precisam ser declaradas com os mesmos argumentos nos dois lados (declarar com argumentos diferentes causa `PRECONDITION_FAILED`).
- **Reconciliação** — verifica A4 lendo a outbox; precisa da consulta somente leitura de linhas pendentes exposta pelo módulo, pois a representação de `enviada` depende da estratégia. Hospeda a suíte A-F1 a A-F7, que usa o módulo como dependência de teste.

## Riscos e observações

- A decisão D9 separou a mensageria em `mensageria-outbox` e `mensageria-inbox` e descartou o módulo único `mensageria-core`. O spec de entrada (`spec-caso-a-outbox-inbox.md`) e as skills `registro-de-adrs` e `testes-de-falha-resiliencia` ainda citam `mensageria-core`; essa é uma divergência já resolvida pela D9, e a atualização desses textos é tarefa de documentação para o `plan.md`.
- `README.md` e `.github/workflows/gradle.yml` descrevem Gradle e JDK 8/11 e não correspondem à stack (`AGENTS.md`); ficam fora desta spec.
- A semântica de `min_active_rowversion()` precisa ser confirmada na versão fixada da imagem do SQL Server (R17); a estratégia de ponto de leitura só é considerada correta depois do teste da história 3. A imagem fixada é `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` (ver `plan.md`), e a validação é feita pela suíte do módulo.
- Os valores dos parâmetros configuráveis (backoff, retenção, lease, lote, limites de heap e de memória direta) não têm padrão nas fontes; o `plan.md` os fixa e os registra na documentação do módulo.
- A compatibilidade de Micrometer, Testcontainers e Toxiproxy com Java 21 (D6) está evidenciada em `research.md` pelo bytecode das versões fixadas (major 52 a 55, executável em Java 21).
- Os nomes das métricas e a definição de lag (idade da linha pendente mais antiga) são definidos por esta spec; a skill de domínio fixa apenas as três grandezas.
- Os critérios que dependem do detentor da decisão final entre estratégias (R16) ficam completos com o ADR `Proposto`; a mudança para `Aceito` é da pessoa decisora.
- Se o código da PoC é descartável ou evolui para o starter interno é pergunta sem decisão, não bloqueia esta spec; o isolamento do módulo o mantém reaproveitável.
