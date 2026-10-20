# 0001. Escolher a estratégia de relay da outbox

- **Status:** Proposto
- **Data:** 2026-10-14
- **Decisores:** pessoa decisora da PoC (escolha pendente)

## Contexto

O valor de `IDENTITY` do SQL Server é atribuído antes do commit: uma transação com `seq = 10` pode confirmar depois de outra com `seq = 11`. Um relay que lê "tudo maior que o último `seq`" pode pular a linha 10 e, no mesmo `aggregateKey`, publicar a mensagem mais nova antes da mais antiga. O relay do `mensageria-outbox` precisa publicar at-least-once, preservar a ordem por `aggregateKey`, não pular linhas confirmadas fora da ordem de `seq` e reiniciar a qualquer momento sem intervenção manual (spec de entrada `spec-caso-a-outbox-inbox.md`, §8, §12 e §13; decisões D5 e D8 em `.agents/context/discovery-answers.md`).

As duas estratégias usam recursos do SQL Server ausentes no H2 e estão implementadas atrás da interface `Relay`, selecionáveis por `outbox.relay.strategy`, para que a comparação use medições.

## Alternativas consideradas

### Estratégia 1: claim (`outbox.relay.strategy=claim`)

`SELECT TOP (lote) ... WITH (UPDLOCK, READPAST, ROWLOCK)` por `seq` na transação do ciclo, publicação em ordem, `sent_at` marcado depois de cada confirm e commit ao final do lote. `READPAST` ignora linhas de transações abertas, então uma sonda `READUNCOMMITTED` de linha anterior pendente do mesmo `aggregateKey` (índice `IX_outbox_aggregate`) retém a mensagem posterior da chave.

- Vantagens: estado de cada linha explícito na própria linha (`sent_at`); várias instâncias poderiam repartir o trabalho; não depende de `rowversion`.
- Desvantagens: `UPDATE` por linha publicada e bloqueios `U`/`X` de linha mantidos durante a publicação do lote; a ordem por `aggregateKey` depende da sonda; cada linha atualizada recebe novo `rv`.

### Estratégia 2: ponto de leitura (`outbox.relay.strategy=read-point`)

Limite seguro lido de `MIN_ACTIVE_ROWVERSION()` antes da leitura; leitura de `rv > ponto AND rv < limite` em ordem de `rv`; o ponto (`outbox_read_point`) avança até o último `rv` do prefixo contíguo publicado. A outbox só recebe `INSERT` e a limpeza remove em lote as linhas com `rv <= last_rv`.

- Vantagens: sem escrita na outbox durante a publicação e sem bloqueios de linha mantidos pelo relay; ordem por `aggregateKey` natural, porque as duas linhas de uma chave entram na faixa lida somente depois que ambas confirmam.
- Desvantagens: `MIN_ACTIVE_ROWVERSION()` vale para o banco inteiro, e uma transação aberta longa em qualquer tabela com `rowversion` atrasa a publicação; um conjunto limitado de linhas publicadas à frente do ponto fica em memória e é republicado depois de um reinício; o ponto de leitura é estado a mais.

## Decisão

Nenhuma estratégia está escolhida. O módulo mantém as duas implementações e a escolha é da pessoa decisora, com base nas observações abaixo. Este ADR passa a `Aceito` com a estratégia escolhida, ou a `Rejeitado`.

## Consequências

- Enquanto a decisão não for tomada, cada implantação define `outbox.relay.strategy`; a configuração ausente ou inválida impede a inicialização.
- Trocar a estratégia de uma implantação com linhas pendentes exige esvaziar a outbox ou reiniciar o ponto de leitura para zero (ver `data-model.md` da spec).
- Escolhida uma estratégia, a outra pode ser removida do módulo, junto dos métodos exclusivos do dialeto e dos testes específicos.

## Observações da PoC

Nenhum número de desempenho é meta: os dados são observação para a decisão. Ambiente: SQL Server `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`, RabbitMQ `rabbitmq:4.3.6-management` atrás do Toxiproxy `2.10.0`, testes de integração do artefato `mensageria-outbox-core` executados com `mvn -pl mensageria-outbox/core verify` a partir da raiz, com Docker.

### Tamanho de cada estratégia

Contagem estática do código-fonte do módulo.

| | claim | ponto de leitura |
|---|---|---|
| Linhas de código da classe da estratégia (sem comentários nem linhas em branco) | 72 (`ClaimRelay`) | 72 (`ReadPointRelay`) |
| Linhas de código dos métodos exclusivos de `SqlServerOutboxDialect` (sem as constantes SQL) | 46 | 78 |
| Execuções de teste que exercitam a estratégia (classes do pacote `relay` e `MinActiveRowversionIT`; método parametrizado conta uma vez por estratégia) | 59 | 63 |

### Dados que dependem de execução com Docker

Os valores abaixo não constam deste ADR; a coleta é trabalho posterior, em estação com Docker, e os números entram aqui antes de a decisão passar a `Aceito`. `RelayObservationsIT` acrescenta a `mensageria-outbox/core/target/relay-observations.txt`, para cada estratégia, os dados dos três primeiros itens, e `MinActiveRowversionIT` grava `mensageria-outbox/core/target/min-active-rowversion-observations.txt`:

- cenário de commits fora da ordem de `seq` (mensagem da transação aberta pulada ou não);
- pico de pendentes e de lag, retries e tempo de recuperação em uma queda do broker com 50 linhas;
- bloqueios de linha `U`/`X` mantidos durante a publicação de um lote e linhas atualizadas na outbox (contenção);
- semântica de `MIN_ACTIVE_ROWVERSION()` na imagem `2019-CU32-ubuntu-20.04`: `@@DBTS` e `MIN_ACTIVE_ROWVERSION()` sem transações abertas, com uma transação aberta e depois do commit.

A política de limpeza segue a estratégia: na claim remove as linhas com `sent_at` vencido; no ponto de leitura, as linhas com `rv <= last_rv` vencidas; nenhuma remove linha pendente.
