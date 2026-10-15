---
name: functional-map
description: Mapa dos cinco domínios da PoC outbox/inbox (Mensageria Outbox, Mensageria Inbox, Pagamentos, Extrato, Reconciliação) com fronteiras, dependências, dependências técnicas, regras inferidas, confiança e ordem de implementação sugerida.
metadata:
  author: clovis-cli
  responsibility: "Map of identification of the business domains (bounded contexts), their boundaries, dependencies and suggested implementation order. Index of domains for skill generation and the spec-driven flow; it does not detail business rules nor duplicate the cross-cutting decisions, which live in the discovery-answers.md."
---

# Mapa funcional

Projeto greenfield sem código: a evidência de todos os domínios vem do material fornecido (`spec-caso-a-outbox-inbox.md`, indexado em `business-input.md`, Fonte 1) e da organização pedida pelo usuário para a mensageria (`discovery-answers.md`, D9). As decisões transversais (Java 21 nos domínios, exceto o Mensageria Outbox, que tem Java 8 como piso; Maven, Spring Boot 3.x, ADRs, SQL Server em Docker para o relay) estão em `discovery-answers.md`; o efeito de cada uma está refletido nos blocos abaixo.

A mensageria é dividida em dois domínios independentes, um por lado da entrega: o produtor (`mensageria-outbox`) e o consumidor (`mensageria-inbox`). Nenhum depende do outro; cada um carrega a própria definição do envelope padrão.

## 1. Mensageria Outbox (`mensageria-outbox`)

**Objetivo de negócio:** garantir que a mensagem de um fato de negócio exista se, e somente se, o dado de negócio do produtor existir, por meio de envelope padrão, outbox gravada na transação local, relay com publisher confirms, lease de relay único, retry/backoff de publicação, retenção da outbox e métricas de publicação.

**Evidência no material fornecido:** `business-input.md` Fonte 1 — §2 (linhas 16-26), §4 (36-47), §5.1 tabela `outbox` (51-71), §6.1 (95-106), RF-A2 a RF-A5, RF-A9 e RF-A11 (§7, 122-132), §8 (134-146), §9 (148-153), tarefas 2 e 3 (§12, 222-223).

**Dependências entre domínios:** nenhuma.

**Regras inferidas:**
- Publicação at-least-once com publisher confirms; a linha da outbox só é considerada enviada após o confirm.
- Ordem preservada por `aggregateKey`; ordem global não é exigida.
- No máximo um relay ativo por banco de origem, controlado por lease; o relay só roda no banco primário/ativo (configurável).
- Duas estratégias de relay atrás de uma mesma interface (claim com `UPDLOCK`/`READPAST`/`ROWLOCK`; ponto de leitura com `rowversion` e `min_active_rowversion()`), para tratar commits fora da ordem de `seq`. A escolha final é registrada em ADR.
- Retenção/limpeza configurável da outbox, sem afetar a ordem nem a segurança do relay.
- Retry com backoff na publicação, sem perda de mensagem.
- Métricas de publicação: lag da outbox, pendentes e tentativas de retry. `correlationId` e `traceId` propagados em headers e logs até a publicação.
- O módulo não depende de Spring Boot e conhece o RabbitMQ apenas por interface de publicação; o acesso a banco é isolado por dialeto.
- O relay é reiniciável a qualquer momento sem intervenção manual e não cresce em memória sem limite.

**Dependências externas relevantes:**
- SQL Server — banco da outbox, do lease e do relay de Pagamentos, executado em Docker; as duas estratégias de relay dependem dos recursos `UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion`/`min_active_rowversion()`. O H2 não oferece esses recursos e não é usado para o relay.
- RabbitMQ — broker com filas quorum e publisher confirms (acessado por adaptador).
- Micrometer — métricas de publicação.
- Java 8 (piso) e Maven multi-módulo — plataforma e build; suíte verificada nos JDKs 8, 11, 17, 21 e 25; sem Spring Boot no módulo, com adaptador fino para Spring Boot 3.x. Os demais domínios seguem em Java 21.

**Dependências técnicas do domínio:**
- Ambiente Docker com SQL Server e RabbitMQ (e Toxiproxy para testes) — sem ele o relay não é executável nem testável contra os recursos do SQL Server.
- Testcontainers e Toxiproxy — sem eles os cenários de falha do lado produtor (A-F1, A-F3, A-F4, A-F5, A-F7) não são repetíveis.
- ADRs — registram a escolha entre as estratégias de relay (critério de conclusão).
- Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey` — sem ela a rastreabilidade ponta a ponta (RF-A11) não existe.

**Confiança:** alta para outbox, envelope e relay (requisitos explícitos); média para o envelope duplicado em cada módulo, que decorre da separação pedida pelo usuário e não de um requisito do spec.

**Lacunas abertas:** o spec §14 pergunta se o código da PoC é descartável ou evolui para o starter interno. Não bloqueia a especificação.

## 2. Mensageria Inbox (`mensageria-inbox`)

**Objetivo de negócio:** garantir que cada mensagem recebida produza no máximo um efeito de negócio no consumidor, por meio de consumidor com ack manual, inbox gravada na transação local do efeito, dedup por `messageId`, retry/backoff, DLQ e métricas de consumo.

**Evidência no material fornecido:** `business-input.md` Fonte 1 — §2 (16-26), §4 (36-47), §5.2 tabela `inbox` (73-91), §6.1 (95-106), RF-A6 a RF-A8, RF-A10 e RF-A11 (§7, 126-132), §9 (148-153), tarefa 2 (§12, 222), cenários A-F2 e A-F6 e "Ordem por agregado" (§11, 190-193, 207-209, 214-217). A serialização por chave e o comportamento da chave em falha vêm de decisão humana (`discovery-answers.md`, D10 e D11).

**Dependências entre domínios:** nenhuma.

**Regras inferidas:**
- O efeito de negócio e o registro na inbox são gravados na mesma transação local; o ack só ocorre depois do commit.
- Dedup por `messageId` na inbox; mensagem repetida é descartada com ack sem reaplicar o efeito.
- Mensagens do mesmo `aggregateKey` são aplicadas na ordem de entrega; ordem global não é exigida.
- O processamento é serializado por `aggregateKey` dentro do processo: cada chave tem a sua fila de execução em memória e só uma mensagem da chave é processada por vez; chaves diferentes rodam em paralelo. Se uma thread já processa uma mensagem da chave, a seguinte da mesma chave aguarda o fim da anterior (commit e ack, ou falha). A fila por chave respeita o limite de mensagens em voo.
- Retry com backoff e DLQ após limite configurável de tentativas. Durante o retry, a chave espera (a ordem é preservada) e as demais chaves seguem. Depois que a mensagem vai para a DLQ, as mensagens seguintes da mesma chave também vão para a DLQ, para revisão manual; mensagem inválida (veneno) não impede as demais chaves.
- Métricas de consumo: duplicatas descartadas, mensagens em DLQ e tentativas de retry. `correlationId` e `traceId` propagados nos logs do consumo.
- O módulo não depende de Spring Boot e conhece o RabbitMQ apenas por interface de consumo; o acesso à inbox é isolado por dialeto (H2 em Extrato).
- O consumidor não cresce em memória sem limite (limite de mensagens em voo e de heap/memória direta).

**Dependências externas relevantes:**
- H2 em arquivo — banco da inbox onde os recursos exclusivos do SQL Server não são necessários; o módulo precisa de dialeto para ele. O DDL do spec (`UNIQUEIDENTIFIER`, `DATETIME2`) precisa de equivalente H2.
- RabbitMQ — broker com filas quorum e ack manual (acessado por adaptador).
- Micrometer — métricas de consumo.
- Java 21 e Maven multi-módulo — plataforma e build; sem Spring Boot no módulo, com adaptador fino para Spring Boot 3.x.

**Dependências técnicas do domínio:**
- Ambiente Docker com RabbitMQ (e Toxiproxy para testes) — sem ele o consumidor não é executável nem testável contra filas quorum e ack manual.
- Testcontainers e Toxiproxy — sem eles os cenários de falha do lado consumidor (A-F2, A-F6) não são repetíveis.
- Consumidor com ack manual — sem ele a garantia de ack após commit não existe.
- Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey` — sem ela a rastreabilidade ponta a ponta não existe.

**Confiança:** alta para inbox, dedup, ack e retry/DLQ (requisitos explícitos); média para o envelope duplicado em cada módulo e para o dialeto H2, que decorrem da separação pedida e da restrição declarada, não de requisito do spec.

**Lacunas abertas:** nenhuma.

## 3. Pagamentos (`pagamentos`)

**Objetivo de negócio:** receber a solicitação de pagamento, registrá-lo e publicar o fato `PagamentoRecebido`, de modo que o pagamento e a mensagem existam juntos ou não existam.

**Evidência no material fornecido:** `business-input.md` Fonte 1 — §5.1 (51-71), §6.2 (108-116), RF-A1 (§7, 122), cenários "Fluxo feliz" e "Falha na gravação do pagamento" (§11, 172-182).

**Dependências entre domínios:** `mensageria-outbox` (outbox e relay).

**Regras inferidas:**
- `pagamento` e a linha da `outbox` são gravados na mesma transação local; falha na transação não deixa nenhum dos dois.
- Status inicial `RECEBIDO`; valor monetário com duas casas decimais; `cooperadoId` numérico.
- O evento `PagamentoRecebido` v1 carrega `pagamentoId`, `cooperadoId` e `valor` e usa `pagamentoId` como `aggregateKey`.
- O banco de Pagamentos é separado do de Extrato; não há transação local entre os dois.

**Dependências externas relevantes:**
- SQL Server em Docker — banco de Pagamentos (tabelas `pagamento` e `outbox`), escolhido porque o relay exige recursos que o H2 não tem; substitui o H2 em arquivo apenas neste domínio.
- Spring Boot 3.x — framework da aplicação, com adaptador fino do `mensageria-outbox`.
- Java 21 e Maven.

**Dependências técnicas do domínio:**
- Ambiente Docker com SQL Server — sem ele a aplicação não tem banco.
- Relay do `mensageria-outbox` em execução — sem ele o pagamento é gravado mas o evento nunca chega ao Extrato.

**Confiança:** alta.

**Lacunas abertas:** nenhuma.

## 4. Extrato (`extrato`)

**Objetivo de negócio:** consumir `PagamentoRecebido` e registrar o lançamento previsto no extrato do cooperado, exatamente uma vez por pagamento.

**Evidência no material fornecido:** `business-input.md` Fonte 1 — §5.2 (73-91), RF-A6 a RF-A8 (§7, 126-129), cenários A-F2 e A-F6 e "Ordem por agregado" (§11, 190-193, 207-209, 214-217).

**Dependências entre domínios:** `mensageria-inbox` (inbox, consumidor, retry/DLQ); `pagamentos` (contrato do evento `PagamentoRecebido`).

**Regras inferidas:**
- `extrato_lancamento` e o registro na `inbox` são gravados na mesma transação local; o ack só ocorre depois do commit.
- `messageId` já presente na `inbox` leva a descarte com ack, sem reaplicar o efeito.
- `UNIQUE (pagamento_id)` em `extrato_lancamento` é segunda barreira contra duplicidade.
- Situação inicial do lançamento: `PREVISTO`.
- Mensagens do mesmo `aggregateKey` são aplicadas em ordem: o consumidor do `mensageria-inbox` processa uma mensagem por chave por vez, e chaves diferentes em paralelo.
- Mensagem inválida (veneno) vai para a DLQ após o limite de tentativas, sem bloquear as demais chaves; as mensagens seguintes da mesma chave também vão para a DLQ, para revisão manual.

**Dependências externas relevantes:**
- H2 em arquivo — banco de Extrato (tabelas `extrato_lancamento` e `inbox`); o domínio não usa os recursos exclusivos do SQL Server, então vale a restrição declarada. O DDL do spec (tipos do SQL Server como `UNIQUEIDENTIFIER` e `DATETIME2`) precisa de equivalente H2.
- RabbitMQ — origem das mensagens (via `mensageria-inbox`).
- Spring Boot 3.x — framework da aplicação, com adaptador fino do `mensageria-inbox`.
- Java 21 e Maven.

**Dependências técnicas do domínio:**
- Arquivo H2 próprio, separado do banco de Pagamentos — garante que não exista transação local entre as duas aplicações.
- Consumidor com ack manual do `mensageria-inbox` — sem ele a garantia de ack após commit não existe.

**Confiança:** alta para o fluxo de consumo; média para o uso de H2 em Extrato, que resulta da leitura combinada da restrição declarada e da decisão de usar SQL Server em Docker apenas onde os recursos são necessários.

**Lacunas abertas:** nenhuma.

## 5. Reconciliação (`reconciliacao`)

**Objetivo de negócio:** verificar, de forma independente do fluxo, as invariantes A1 a A4 entre Pagamentos e Extrato ao final de todas as falhas injetadas.

**Evidência no material fornecido:** `business-input.md` Fonte 1 — §10 (159-168), §13 (230-235), tarefa 6 (§12, 226).

**Dependências entre domínios:** `pagamentos` (lê `pagamento` e `outbox`); `extrato` (lê `extrato_lancamento`).

**Regras inferidas:**
- A1: todo `pagamento` com status `RECEBIDO` tem exatamente um `extrato_lancamento`, eventualmente.
- A2: não existe `extrato_lancamento` sem `pagamento` correspondente.
- A3: mensagens do mesmo `aggregateKey` são aplicadas em ordem.
- A4: nenhuma linha fica indefinidamente na `outbox` sem ser publicada, enquanto o broker estiver disponível.
- É escrito a partir das invariantes, e não do código do fluxo, de preferência por quem não implementou o fluxo; é um job independente que só lê.

**Dependências externas relevantes:**
- SQL Server em Docker — leitura do banco de Pagamentos (`pagamento` e `outbox`).
- H2 em arquivo — leitura do banco de Extrato.
- Java 21 e Maven (módulo próprio); Spring Boot 3.x é opcional para o job.

**Dependências técnicas do domínio:**
- Acesso de leitura aos dois bancos — sem ele nenhuma invariante é verificável.
- Suíte de testes de falha (Testcontainers e Toxiproxy) — o reconciliador é o oráculo dos cenários A-F1 a A-F7.

**Confiança:** alta para as invariantes; média para o formato de execução do job (agendamento ou execução sob demanda), que o spec não define.

**Lacunas abertas:** o spec §14 pergunta quem escreve o reconciliador; é decisão organizacional e não bloqueia a especificação.

## Sugestão de ordem de implementação

1. `mensageria-outbox` — envelope, outbox, relay com as duas estratégias, lease, publicação com confirms, retry de publicação, retenção e métricas.
2. `mensageria-inbox` — envelope, consumidor com ack manual, inbox, dedup, retry/DLQ e métricas; independente do `mensageria-outbox`, pode ser feito em paralelo.
3. `pagamentos` — produtor do evento, sobre o `mensageria-outbox`.
4. `extrato` — consumidor do evento, sobre o `mensageria-inbox`.
5. `reconciliacao` — verifica as invariantes sobre os dois bancos e valida a suíte de falhas.
