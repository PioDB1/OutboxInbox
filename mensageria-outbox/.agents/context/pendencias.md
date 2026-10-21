---
name: pendencias
description: Pendências conhecidas do componente mensageria-outbox (núcleo mensageria-outbox-core e build) — defeitos encontrados na validação de 2026-10-20 e registrados sem correção, cada um com arquivo, efeito, severidade, forma de verificação e decisão; o núcleo da inbox (lado consumidor, documentado pela skill mensageria-inbox), a implementar no mensageria-outbox-core junto da outbox e da auto-configuração Spring; e o estado das chaves bloqueadas na DLQ, sem tratamento decidido.
metadata:
  author: clovis-cli
---

# Pendências do componente

Defeitos encontrados na validação de 2026-10-20 (decisão D15 de `discovery-answers.md`), de P1 a P5; o núcleo da inbox a implementar no `mensageria-outbox-core`, em P6; e o estado das chaves bloqueadas na DLQ, em P7. Caminhos relativos a `mensageria-outbox/`. "Confirmado no código" indica leitura do código-fonte; "não reproduzido em teste" indica que nenhum teste exercita o defeito.

## P1. Conexão órfã por queda do broker

- **Arquivo:** `core/src/main/java/br/com/db1/poc/outbox/rabbit/RabbitMessagePublisher.java`, método `openChannel`.
- **Efeito:** com `connection.isOpen()` falso, o método abre uma conexão nova e substitui a referência sem fechar a anterior. Com a recuperação automática de conexão ligada (configuração exigida do hospedeiro), a conexão anterior está em recuperação quando a nova abre; ao concluir a recuperação ela continua aberta, com os canais que recupera, e `close()` só fecha a conexão mais recente. Cada queda do broker em que a nova conexão abre antes do fim da recuperação deixa uma conexão órfã no broker e no processo.
- **Severidade:** média.
- **Verificação:** confirmado no código; não reproduzido em teste.
- **Decisão:** registrado em 2026-10-20, sem correção nesta rodada.

## P2. Inanição de chaves atrás de linhas que falham sempre

- **Arquivos:** `core/src/main/java/br/com/db1/poc/outbox/dialect/sqlserver/SqlServerOutboxDialect.java` (`CLAIM_PENDING` e `READ_RANGE`), `core/src/main/java/br/com/db1/poc/outbox/relay/ClaimRelay.java` e `core/src/main/java/br/com/db1/poc/outbox/relay/ReadPointRelay.java`.
- **Efeito:** uma linha que falha em toda tentativa (por exemplo, `type` sem fila ligada à routing key, devolvida por `basic.return` sob `mandatory=true`) continua pendente; o backoff só espaça as tentativas.
  - Claim: o ciclo lê `SELECT TOP (batch-size) ... WHERE sent_at IS NULL ORDER BY seq`. As linhas que falham e as posteriores da mesma chave, retidas pela ordem por `aggregateKey`, ficam no início dessa leitura. Quando elas somam `batch-size` linhas, nenhuma linha posterior é lida, e todas as demais chaves ficam sem publicação.
  - Ponto de leitura: o ponto só avança pelo prefixo contíguo publicado e para antes da linha que falha. O ciclo lê `TOP (batch-size)` linhas com `rv` acima do ponto; as publicadas à frente continuam nessa janela, que não se move. Uma única linha que falha sempre basta: depois de `batch-size - 1` linhas posteriores, nenhuma linha mais nova, de qualquer chave, é lida.
- **Conflito:** contraria R12 da skill de domínio e a invariante A4, segundo as quais nenhuma linha fica indefinidamente sem publicação enquanto o broker estiver disponível.
- **Severidade:** alta.
- **Verificação:** confirmado no código; não reproduzido em teste.
- **Decisão:** registrado em 2026-10-20, sem correção nesta rodada.

## P3. `backoff.multiplier=NaN` aceito

- **Arquivos:** `core/src/main/java/br/com/db1/poc/outbox/relay/RelayConfig.java` (validação `backoffMultiplier < 1.0`) e `core/src/main/java/br/com/db1/poc/outbox/relay/BackoffPolicy.java` (`delayAfter`).
- **Efeito:** `Double.parseDouble("NaN")` é aceito e `NaN < 1.0` é falso, então a configuração passa na validação. Em `delayAfter`, a primeira falha usa `Math.pow(NaN, 0) = 1` e espera `backoff.initial`; da segunda falha consecutiva em diante o intervalo é `NaN`, `Math.min(NaN, max)` é `NaN` e a conversão para `long` dá zero. A chave fica sem backoff e é retentada a cada ciclo (tempestade de tentativas).
- **Severidade:** média (exige configuração inválida).
- **Verificação:** confirmado no código; não reproduzido em teste.
- **Decisão:** registrado em 2026-10-20, sem correção nesta rodada.

## P4. Defeitos de baixa severidade no núcleo

- **`PublishableMessage.equals`/`hashCode`** — `core/src/main/java/br/com/db1/poc/outbox/publish/PublishableMessage.java`. O `payload` (`byte[]`) é comparado por referência (`Objects.equals`, `Objects.hash`): duas mensagens com o mesmo conteúdo em arrays distintos são diferentes. Afeta comparações em testes e coleções; não afeta a publicação.
- **`OutboxSchemaInstaller.install` sem `autoCommit`** — `core/src/main/java/br/com/db1/poc/outbox/schema/OutboxSchemaInstaller.java`. O método executa os lotes do script sem ligar `autoCommit` nem chamar `commit`. Com um `DataSource` que entrega conexões com `autoCommit=false`, o DDL fica em uma transação nunca confirmada e é desfeito no fechamento da conexão, sem erro.
- **`RelayRunner.close` libera o lease após timeout** — `core/src/main/java/br/com/db1/poc/outbox/relay/RelayRunner.java`. O `join` espera `confirm-timeout` + 5 s; se a thread do relay continua viva (por exemplo, presa em uma chamada JDBC ou aguardando um confirm), o lease é liberado mesmo assim. Outra instância pode assumir enquanto o ciclo antigo termina, o que gera publicações duplicadas, absorvidas pelo dedup do consumidor.
- **Métrica de pendentes no ponto de leitura** — `core/src/main/java/br/com/db1/poc/outbox/dialect/sqlserver/SqlServerOutboxDialect.java` (`PENDING_AHEAD_OF_READ_POINT`). A contagem inclui toda linha com `rv` acima do ponto, inclusive as já publicadas à frente dele. `outbox.pending` e o `PendingQuery` usado pelo reconciliador superestimam as pendentes enquanto uma linha retém o ponto.
- **Severidade:** baixa.
- **Verificação:** confirmados no código; não reproduzidos em teste.
- **Decisão:** registrados em 2026-10-20, sem correção nesta rodada.

## P5. Build e testes

- **Versão do `extra-enforcer-rules`** — `pom.xml`. A dependência do `maven-enforcer-plugin` fixa `extra-enforcer-rules` 1.8.0 no próprio plugin, e a propriedade `extra-enforcer-rules.version` (1.12.1) não é usada em nenhum POM. A versão efetiva é 1.8.0, e alterar a propriedade não tem efeito.
- **`ComposeImagesTest` comentado** — `core/src/test/java/br/com/db1/poc/shared/support/ComposeImagesTest.java`. A classe inteira está comentada, então nada verifica a igualdade entre as imagens do `compose.yaml` e as da classe `Images`, que `README.md` (seção "Versões fixadas") atribui a ela. O código comentado resolve o `compose.yaml` como `${basedir}/../compose.yaml`, que a partir de `core/` aponta para `mensageria-outbox/compose.yaml`, e não para o arquivo da raiz do repositório.
- **Severidade:** baixa.
- **Verificação:** confirmados nos arquivos.
- **Decisão:** registrados em 2026-10-20, sem correção nesta rodada.

## P6. Núcleo da inbox a implementar no `mensageria-outbox-core`

O lado consumidor faz parte da solução outbox/inbox e é implementado no artefato `mensageria-outbox-core`, junto da outbox, com a auto-configuração Spring do consumidor nos artefatos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`. A skill `.agents/skills/mensageria-inbox/SKILL.md` é a fonte das regras (R1 a R18); `Inbox.puml` mostra o fluxo de idempotência; `spec-caso-a-outbox-inbox.md` traz a tabela `inbox` (§5), o envelope (§6.1), RF-A6 a RF-A8, RF-A10 e RF-A11 e os cenários A-F2 e A-F6 (§11).

- **O quê:** o componente não tem consumidor nem inbox. Falta o núcleo do lado consumidor: leitura do envelope, consumidor com ack manual, inbox e dedup por `messageId` na transação local, serialização por `aggregateKey`, retry com backoff, DLQ, métricas e logs de consumo, dialetos H2 e SQL Server, e a auto-configuração Spring.
- **Onde:** `core/src/main/java/br/com/db1/poc/outbox/`, que só tem o lado produtor; `../mensageria-outbox-spring-boot-shared/src/main/java/br/com/db1/poc/outbox/spring/`, `../mensageria-outbox-spring-boot3/` e `../mensageria-outbox-spring-boot4/`, que só integram a outbox (P1 de `../mensageria-outbox-spring-boot-shared/.agents/context/pendencias.md` e P2 de `../mensageria-outbox-spring-boot3/.agents/context/pendencias.md` e de `../mensageria-outbox-spring-boot4/.agents/context/pendencias.md`).
- **Efeito:** o `demooutboxinbox` consome a fila `movimentos.movimento-registrado` com código próprio (`MovimentoConsumoService`, em `POST /movimentos/consumo`): `basicGet` pelo `amqp-client`, inbox gravada em transação local e ack depois do commit, sem retry com backoff, DLQ, serialização por `aggregateKey` nem métricas de consumo. Uma mensagem que falha sempre volta para a fila até o limite de entregas da fila quorum (20), quando o broker a descarta.
- **Verificação:** confirmado na estrutura do componente.
- **Decisão:** decisão humana de 2026-10-20, na spec `005-pagamentos-consumo-movimento-registrado` do `demooutboxinbox`, de registrar a ausência como pendência; decisão humana de 2026-10-21 de implementar o núcleo da inbox no `mensageria-outbox-core`, no mesmo artefato da outbox e sem novo artefato para importação, com o mesmo contexto de beans do Spring da outbox. Escopo, plano, tarefas e casos de teste na spec [`003-mensageria-outbox-nucleo-inbox`](../specs/003-mensageria-outbox-nucleo-inbox/spec.md). A substituição do consumo próprio do demo é a P1 de `../../demooutboxinbox/.agents/context/pendencias.md`.

## P7. Estado das chaves bloqueadas por mensagem na DLQ (R10b)

- **O quê:** depois que uma mensagem vai para a DLQ por exceder o limite de tentativas, as mensagens seguintes da mesma `aggregateKey` também vão para a DLQ (R10b da skill `mensageria-inbox`). Não está decidido onde esse estado de chave bloqueada é guardado, se sobrevive ao reinício do consumidor e como a chave é liberada depois da revisão manual.
- **Onde:** `core/src/main/java/br/com/db1/poc/inbox/`, na implementação do núcleo da inbox da P6.
- **Efeito:** a implementação da P6 guarda as chaves bloqueadas em memória, atrás de uma interface, e não oferece liberação: a chave segue bloqueada até o consumidor reiniciar. Depois do reinício, a próxima mensagem da chave é aplicada sem a mensagem que foi para a DLQ, o que quebra a ordem da chave.
- **Verificação:** confirmado na skill `mensageria-inbox` (R10b, que não define o armazenamento) e em `spec-caso-a-outbox-inbox.md` (RF-A8 e A-F6, que também não o definem).
- **Decisão:** decisão humana de 2026-10-21 — registrar como pendência, sem decidir o tratamento; a implementação da P6 só cobre o bloqueio durante a vida do processo.
