# Dependências futuras — 001-mensageria-outbox-implementacao-modulo

Dívidas intencionais entre tarefas: construtos temporários e ajustes que uma tarefa deixa para outra. A spec não fecha com itens `open` ou `planned`.

## FD-1 — registro de log provisório do relay
- origin: T5
- destination: T11
- status: resolved
- constructs: `RelayRunner.LOG` (`java.lang.System.Logger`)
- expected resolution: substituir o `System.Logger` por SLF4J em todo o relay, com MDC `messageId`, `correlationId`, `aggregateKey` e `traceId` definido em volta de cada tentativa de publicação em `RelayRunner.publish` e limpo ao fim; remover o marcador do código.

## FD-2 — `RelayFactory.create` sem `MeterRegistry`
- origin: T5
- destination: T11
- status: resolved
- constructs: `RelayFactory.create(RelayConfig, DataSource, MessagePublisher, String)`
- expected resolution: a assinatura passa a ser `create(RelayConfig, DataSource, MessagePublisher, MeterRegistry, String)`, conforme `contracts/api-modulo.md`; o registro chega a `ClaimRelay`, `ReadPointRelay` e `RelayRunner`, que atualizam `outbox.lag.seconds` e `outbox.pending` a cada ciclo e incrementam `outbox.publish.retries` em `RelayRunner.publish` quando a chave já tem falhas registradas em `BackoffPolicy.failures`; remover o marcador do código.

## FD-3 — asserção de `PendingQuery` no caso TC-28
- origin: T7
- destination: T11
- status: resolved
- expected resolution: `ReadPointRelayIT.estrategiaSoInsereNaOutbox` confere a contagem de pendentes por SQL; com `PendingQuery` entregue, acrescentar a asserção de que a consulta da estratégia de ponto de leitura devolve contagem zero depois da publicação.

## FD-4 — valores de `MIN_ACTIVE_ROWVERSION()` para o ADR `0001`
- origin: T7
- destination: T14
- status: resolved
- expected resolution: decisão humana: a coleta dos valores observados e o registro deles no ADR `0001` ficam fora desta spec, como trabalho futuro com Docker disponível. `MinActiveRowversionIT` grava os valores em `target/min-active-rowversion-observations.txt` quando executado com Docker, e o ADR `0001` descreve essa pendência na seção "Observações da PoC".
