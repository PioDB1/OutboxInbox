# Spec: Inclusão dupla de movimentos

## Visão geral

Implementa o endpoint `POST /movimentos/inclusao_dupla`, que grava duas movimentações na tabela `movimento` e duas mensagens na outbox dentro da mesma transação Spring. O endpoint permite simular falhas em diferentes pontos do processo para verificar a atomicidade e o comportamento da outbox (rollback de eventos quando a transação falha).

## Domínio

- Slug: `pagamentos`
- Skill: [`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md) — seção "Entidades e dados", subseção "Tabela `movimento` (SQL Server)" e "Evento `MovimentoRegistrado` (versão 1)".
- Skill técnica: [`.agents/skills/integracao-mensageria-outbox-spring-boot/SKILL.md`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md) — seções "Gravação na transação" e "Envio após o commit e recuperação".

## Escopo

**Dentro:**

- Inclusão de duas movimentações em uma única chamada de API, fornecendo os dados de ambas.
- Gravação de ambas as movimentações e de seus respectivos eventos `MovimentoRegistrado` na outbox em uma única transação local.
- Mecanismo para forçar uma exceção:
    - Após a gravação da primeira movimentação e seu evento, mas antes da segunda.
    - Após a gravação de ambas as movimentações e eventos, mas antes do commit.
- Garantia de que os eventos sigam a ordem de criação (sequencial na outbox).
- Garantia de que os eventos só sejam publicados se a transação for confirmada com sucesso.

**Fora:**

- Inclusão de mais ou menos que duas movimentações neste endpoint específico.
- Alteração no esquema da tabela `movimento` ou no contrato do evento `MovimentoRegistrado`.

## Fronteira do domínio

**O que esta spec implementa:**

- Endpoint `POST /movimentos/inclusao_dupla`, que recebe uma lista de exatamente dois objetos de movimentação.
- Lógica no `MovimentoService` para processar a inclusão dupla com suporte a falha forçada.
- Parâmetro de consulta `posicaoFalha` para controlar o ponto da interrupção.

**Pertence a outros domínios ou unidades:**

- Entidade `MovimentoEntity`, repositório e tabelas da outbox (existentes).
- Mecanismo de publicação assíncrona da outbox (biblioteca/infraestrutura existente).

## Histórias de usuário

1. Como desenvolvedor da POC, quero gravar duas movimentações de uma vez para testar a ordem de publicação dos eventos na outbox.
2. Como desenvolvedor da POC, quero simular uma falha após a primeira gravação para confirmar que nem a primeira movimentação nem o seu evento são persistidos/publicados (atomicidade).
3. Como desenvolvedor da POC, quero simular uma falha após as duas gravações para confirmar que nada é persistido/publicado, mesmo que as operações parciais tenham tido sucesso no banco.

## Critérios de aceite

**Requisição:**
`POST /movimentos/inclusao_dupla?posicaoFalha=N`
Corpo: Lista JSON com 2 objetos `MovimentoRequest`.

**Parâmetro `posicaoFalha`:**
- `0` (ou ausente): Sucesso total. Ambas as movimentações gravadas e eventos publicados.
- `1`: Falha após gravar a 1ª movimentação e seu evento na outbox.
- `2`: Falha após gravar a 2ª movimentação e seu evento na outbox, antes do commit.

**Resultados esperados:**

- **Sucesso (`posicaoFalha=0`):**
    - Resposta `201 Created` com lista das duas movimentações.
    - Duas novas linhas na tabela `movimento`.
    - Duas mensagens `MovimentoRegistrado` publicadas no RabbitMQ na ordem correta.
- **Falha na 1ª (`posicaoFalha=1`):**
    - Resposta `500 Internal Server Error`.
    - Nenhuma linha nova na tabela `movimento`.
    - Nenhuma mensagem publicada no RabbitMQ.
- **Falha na 2ª (`posicaoFalha=2`):**
    - Resposta `500 Internal Server Error`.
    - Nenhuma linha nova na tabela `movimento`.
    - Nenhuma mensagem publicada no RabbitMQ.

## Comportamento atual → novo

| Aspecto | Atual | Novo |
|---|---|---|
| Inclusão de movimentos | Apenas individual (`/inclusao`) | Suporte a inclusão dupla atômica (`/inclusao_dupla`) |
| Falha forçada | Apenas ao final da operação única | Escolha do ponto de falha (entre operações ou ao final) |
| Ordem de eventos | Um evento por transação | Dois eventos na mesma transação, preservando ordem de gravação |

## Dependências entre domínios

- Depende do domínio `pagamentos` para a entidade `movimento` e o evento `MovimentoRegistrado`.

## Riscos e observações

- A ordem dos eventos no RabbitMQ é garantida pela sequência de gravação na tabela `outbox` e pelo processamento sequencial do despachante.
- O uso de `posicaoFalha` como parâmetro facilita o teste automatizado ou manual via Swagger.
