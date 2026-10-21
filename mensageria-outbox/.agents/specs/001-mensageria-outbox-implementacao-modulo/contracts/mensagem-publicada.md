# Contrato: mensagem publicada no RabbitMQ

Contrato entre o `mensageria-outbox` (publica) e o `mensageria-inbox` (consome). Cada módulo carrega a própria definição do envelope; este documento é a referência comum do formato no broker.

## Corpo

Os bytes do `payload` da outbox, sem alteração e sem interpretação. `content_type` = `application/octet-stream`.

## Headers AMQP (contrato)

| Header | Tipo AMQP | Origem | Obrigatório |
|---|---|---|---|
| `messageId` | string (UUID) | `Envelope.messageId` | sim |
| `correlationId` | string (UUID) | `Envelope.correlationId` | sim |
| `idempotencyKey` | string | `Envelope.idempotencyKey` | sim |
| `aggregateKey` | string | `Envelope.aggregateKey` | sim |
| `type` | string | `Envelope.type` | sim |
| `schemaVersion` | int | `Envelope.schemaVersion` | sim |
| `occurredAt` | string ISO-8601 UTC com microssegundos (`2026-10-14T16:20:31.123456Z`) | `Envelope.occurredAt` | sim |
| `traceId` | string | `Envelope.traceId` | sim |

## Properties AMQP

| Property | Valor |
|---|---|
| `message_id` | igual ao header `messageId` |
| `correlation_id` | igual ao header `correlationId` |
| `type` | igual ao header `type` |
| `delivery_mode` | `2` (persistente) |
| `content_type` | `application/octet-stream` |

As properties espelham os headers para ferramentas de operação; o consumidor lê os headers.

## Roteamento e topologia

- Exchange: tipo `direct`, durável, nome configurável (`outbox.relay.exchange`, padrão `mensageria.outbox`), declarada por `shared.rabbit.RabbitTopology.declareExchange` (`br.com.db1.poc.shared.rabbit.RabbitTopology`).
- Routing key: o `type` do envelope.
- Publicação com `mandatory=true`; mensagem devolvida por `basic.return` (sem fila ligada à routing key) é falha de publicação e a linha permanece pendente.
- Fila de destino: declarada pela aplicação com `shared.rabbit.RabbitTopology.declareQuorumQueue(channel, queue, exchange, routingKey)`, com argumentos `x-queue-type=quorum`, `durable=true`, `exclusive=false`, `auto-delete=false` e nenhum outro argumento. Quem declarar a mesma fila com argumentos diferentes recebe `PRECONDITION_FAILED`; todo declarante da fila principal repete exatamente esses argumentos.

## Garantias

- At-least-once: o mesmo `messageId` pode chegar mais de uma vez; o consumidor deduplica.
- Ordem por `aggregateKey` na ordem de publicação; nenhuma ordem global.
