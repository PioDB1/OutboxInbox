---
name: discovery-answers
description: Memória da descoberta funcional da PoC outbox/inbox (Pagamentos → Extrato) — objetivo, escopo, restrições (H2 em arquivo onde possível, SQL Server em Docker para o relay, Java 21, Maven, Spring Boot 3.x, ADRs), decisões transversais com destino, módulos de mensageria separados em outbox e inbox e ordem por aggregateKey no consumidor e registro das oito decisões humanas.
metadata:
  author: clovis-cli
  responsibility: "Durable memory of the functional-discovery context and decisions: objective, scope, declared restrictions, validated cross-cutting decisions (with destination), documentation forms to maintain and the log of human decisions resolved in the gap loop. Source re-read by the following stages; its restrictions take precedence over later inferences."
---

# Respostas da descoberta

## Objetivo e escopo

- **Objetivo:** POC (projeto greenfield) que valida o padrão outbox/inbox como substituto da coordenação XA. Duas aplicações, cada uma com seu banco, devem manter consistência eventual correta (sem perda e sem efeito duplicado) sob falhas, sem transação distribuída. Fonte: `spec-caso-a-outbox-inbox.md` §1 (ver `business-input.md`).
- **Escopo (projeto inteiro):** aplicação Pagamentos, aplicação Extrato, dois módulos de mensageria independentes — `mensageria-outbox` (lado produtor: envelope, outbox, relay, lease, publicação com retry/backoff, retenção, métricas de publicação) e `mensageria-inbox` (lado consumidor: envelope, consumidor com ack manual, inbox, dedup, retry/backoff, DLQ, métricas de consumo) — e job de reconciliação.
- **Fora de escopo:** coordenação de vários passos com compensação (Caso B), CDC/Debezium, ponte ActiveMQ ↔ RabbitMQ, replicação/sincronização de bancos e qualquer meta de latência ou throughput (as métricas são observação).

## Restrições declaradas

| Restrição | Origem | Destino |
|---|---|---|
| Base de dados H2 salva em arquivo | Usuário (questionário da sessão) | Convenção em `AGENTS.md`; domínios `mensageria-inbox`, Extrato e Reconciliação. Exceção em D5: o banco de Pagamentos precisa de recursos que o H2 não oferece. |
| Um banco/schema separado por aplicação; sem transação local entre Pagamentos e Extrato | Spec §3 | Convenção em `AGENTS.md`; domínios Pagamentos e Extrato |
| RabbitMQ com filas quorum, publisher confirms e ack manual | Spec §3 | Domínios `mensageria-outbox` (publisher confirms) e `mensageria-inbox` (ack manual); filas quorum em ambos |
| Sem XA, sem JTA, sem transaction manager externo | Spec §3 | Convenção em `AGENTS.md` |
| `mensageria-outbox` e `mensageria-inbox` sem dependência de Spring Boot; adaptadores finos por framework | Spec §3 | Domínios `mensageria-outbox` e `mensageria-inbox` |
| Núcleo conhece o RabbitMQ apenas por interface de publicação (outbox) e de consumo (inbox) | Spec §9 (Portabilidade) | Domínios `mensageria-outbox` e `mensageria-inbox` |
| Nenhum número de desempenho é meta | Spec §3 e §9.1 | Convenção em `AGENTS.md` |
| Relay e consumidor sem crescimento ilimitado de memória; limites explícitos de heap e memória direta | Spec §9 | Domínios `mensageria-outbox` (relay), `mensageria-inbox` (consumidor), Pagamentos e Extrato |
| Relay reiniciável a qualquer momento sem intervenção manual | Spec §9 | Domínio `mensageria-outbox` |

## Decisões transversais validadas

| Id | Decisão | Destino |
|---|---|---|
| D1 | Java 21 como versão mínima suportada, exceto no `mensageria-outbox`, que tem Java 8 como piso. Os módulos `mensageria-outbox` e `mensageria-inbox` não dependem de Spring Boot. | Convenção em `AGENTS.md` |
| D2 | Maven multi-módulo (`mensageria-outbox`, `mensageria-inbox`, pagamentos, extrato, reconciliação). | Convenção em `AGENTS.md` |
| D3 | Spring Boot 3.x (compatível com Java 21) nas aplicações Pagamentos e Extrato; Pagamentos consome o `mensageria-outbox` (Java 8 como piso, usado pelo adaptador de Pagamentos em Spring Boot 3.x) e Extrato consome o `mensageria-inbox`, cada um por adaptador fino. | Domínios Pagamentos e Extrato; convenção em `AGENTS.md` |
| D4 | ADRs como forma de documentação complementar, incluindo a escolha entre as estratégias de relay (spec §8). | `technicalSkills`: `registro-de-adrs` |
| D5 | O relay de Pagamentos usa SQL Server executado em Docker, porque as duas estratégias de relay dependem de recursos próprios do SQL Server (`UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion` com `min_active_rowversion()`), ausentes no H2. Onde esses recursos não são necessários (inbox de Extrato e Reconciliação), vale a restrição de H2 em arquivo. | `technicalSkills`: `ambiente-docker-sqlserver-rabbitmq`; domínios `mensageria-outbox`, `mensageria-inbox`, Pagamentos, Extrato e Reconciliação |
| D6 | Ambiente de teste com Testcontainers (SQL Server, RabbitMQ) e Toxiproxy para injeção de falhas, com suíte repetível dos cenários A-F1 a A-F7. A compatibilidade de Testcontainers e Micrometer é verificada pela suíte do `mensageria-outbox` nos JDKs 8, 11, 17, 21 e 25. | `technicalSkills`: `testes-de-falha-resiliencia` |
| D7 | Observabilidade: logs estruturados com `messageId`, `correlationId`, `aggregateKey`; propagação de `correlationId` e `traceId` nos headers e logs; métricas em Micrometer (publicação no `mensageria-outbox`, consumo no `mensageria-inbox`). | Convenção em `AGENTS.md`; domínios `mensageria-outbox` e `mensageria-inbox` |
| D8 | Ambas as estratégias de relay (claim e ponto de leitura) são implementadas atrás de uma única interface, para comparação. | Domínio `mensageria-outbox` |
| D9 | A mensageria é dividida em dois módulos independentes: `mensageria-outbox` (produtor) e `mensageria-inbox` (consumidor). Cada módulo carrega a própria definição do envelope padrão, de modo que nenhum dependa do outro. | Domínios `mensageria-outbox` e `mensageria-inbox`; convenção em `AGENTS.md` (estrutura de módulos) |
| D10 | O consumidor serializa o processamento por `aggregateKey` dentro do processo: cada chave tem a sua fila de execução em memória e só uma mensagem da chave é processada por vez; chaves diferentes são processadas em paralelo. Quando uma thread já processa uma mensagem de uma chave, a mensagem seguinte da mesma chave aguarda o fim da anterior (commit e ack, ou falha). A fila de execução por chave respeita o limite de mensagens em voo (memória limitada). | Domínio `mensageria-inbox`; domínio Extrato |
| D11 | Falha na mensagem de uma chave: a chave espera durante o retry com backoff, preservando a ordem, e as demais chaves seguem. Depois que a mensagem vai para a DLQ por exceder o limite de tentativas, as mensagens seguintes da mesma chave também vão para a DLQ, para revisão manual. | Domínio `mensageria-inbox`; domínio Extrato |

Implicação de D5: as tabelas e os acessos são isolados por dialeto de banco em cada módulo — o `mensageria-outbox` (outbox, lease e ponto de leitura) usa SQL Server; o `mensageria-inbox` usa o dialeto do banco da aplicação consumidora (H2 em Extrato). Credenciais dos contêineres ficam em variáveis de ambiente ou `.env`, nunca versionadas.

## Formas de documentação a manter

- ADRs (D4), com ao menos um ADR registrando a estratégia de relay escolhida e as observações da PoC.
- Nenhuma outra forma adicional (sem Swagger/OpenAPI, Postman ou Storybook).

## Framework spec-driven concorrente

Nenhum detectado. Evidência: ausência de `.specify/`, `openspec/` e de qualquer artefato de specs, plans ou tasks no repositório (que contém apenas arquivos de template, `README.md`, `LICENSE`, `.gitignore`, `.github/workflows/gradle.yml` e o spec de entrada). Nenhuma decisão necessária.

## Registro das decisões humanas (laço de lacunas)

| Lacuna | Pergunta | Decisão |
|---|---|---|
| g1 · Plataforma Java | Qual a versão mínima de Java a suportar? | Java 21 (D1), exceto o `mensageria-outbox`, com Java 8 como piso e suíte verificada nos JDKs 8, 11, 17, 21 e 25 (compatibilidade de Testcontainers e Micrometer incluída). |
| g2 · Build e módulos | Qual ferramenta de build para o projeto multi-módulo? | Maven multi-módulo (D2). |
| g3 · Aplicações Pagamentos e Extrato | Qual framework nas aplicações? | Spring Boot, versão compatível com o Java escolhido (D3). |
| g4 · Documentação complementar | Quais formas de documentação manter além das skills de negócio? | ADRs, incluindo a estratégia de relay (D4). |
| g5 · Relay (`mensageria-outbox`) | Como tratar as duas estratégias de relay no H2, que não tem `UPDLOCK`/`READPAST` nem `rowversion`? | Usar outra base com os recursos citados, executada em Docker quando necessário. O SQL Server do spec atende (D5). O H2 em arquivo permanece onde os recursos não são exigidos. |
| g6 · Organização da mensageria | Como organizar os domínios de mensageria? | Dois domínios independentes, `mensageria-outbox` e `mensageria-inbox`, para facilitar o entendimento do conceito; o módulo único `mensageria-core` não existe (D9). Instrução livre do usuário. |
| g7 · Mensageria Inbox — ordem por aggregateKey | Duas mensagens do mesmo `aggregateKey` chegam a threads diferentes do consumidor, com a primeira ainda em processamento: qual mecanismo garante que a segunda só seja aplicada depois da primeira? | Serializar por chave no processo: uma fila por `aggregateKey`, chaves diferentes em paralelo (D10). |
| g8 · Mensageria Inbox — retry e ordem | Se a primeira mensagem falha e fica em retry com backoff, a segunda do mesmo `aggregateKey` espera ou é processada antes? | A chave espera durante o retry; depois da DLQ, as mensagens seguintes da mesma chave também vão para a DLQ, para revisão manual (D11). |

## Perguntas do spec §14 sem decisão

- Quem escreve o reconciliador (o spec recomenda alguém que não implementou o fluxo). Decisão organizacional, não bloqueia a especificação.
- Se o código da PoC é descartável ou evolui para o starter interno. Não bloqueia a especificação; o isolamento de `mensageria-outbox` e `mensageria-inbox` já os mantém reaproveitáveis.
