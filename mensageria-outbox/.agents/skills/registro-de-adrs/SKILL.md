---
name: registro-de-adrs
description: Como criar, numerar, revisar e substituir ADRs (Architecture Decision Records) do projeto, incluindo o ADR da escolha entre as estratégias de relay (claim com UPDLOCK/READPAST/ROWLOCK versus ponto de leitura com rowversion e min_active_rowversion) e as observações medidas na PoC. Use ao registrar, documentar, justificar ou alterar qualquer decisão de arquitetura, ao escrever ou atualizar um ADR, ou ao fechar a comparação entre as estratégias de relay.
metadata:
  author: clovis-cli
  type: technical-skill
---

# Registro de ADRs

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar. O critério é a natureza da mudança: uma mudança de comportamento ou de decisão (novo passo, novo critério, novo formato de ADR) pede atualização; um refactor técnico que preserva a regra (renomear arquivo, reorganizar pastas sem mudar o fluxo) não pede. Se esta skill disser X e o repositório fizer Y sem decisão registrada que resolva, não "corrija" nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

ADRs são a forma de documentação complementar adotada pelo projeto (decisão D4 da descoberta). Cada ADR registra **uma** decisão de arquitetura: o contexto, as alternativas, a escolha e as consequências. O projeto não mantém Swagger/OpenAPI, Postman nem Storybook; decisões que não cabem no código ou nas skills vivem em ADR.

O ADR obrigatório da PoC é a escolha entre as duas estratégias de relay (spec §8), cuja conclusão é critério de conclusão da PoC (spec §13). As duas estratégias são implementadas atrás de uma única interface para permitir a comparação (decisão D8).

## Como aplicar

### Onde vivem e como se chamam

- Pasta `docs/adr/` na raiz do repositório.
- Arquivo `NNNN-titulo-em-kebab-case.md`, com numeração sequencial de quatro dígitos sem reaproveitamento (`0001-estrategia-de-relay.md`).
- Índice em `docs/adr/README.md`: uma linha por ADR com número, título, status e data. Atualize o índice no mesmo commit do ADR.
- Texto em português do Brasil, com acentuação preservada.

### Quando abrir um ADR

Abra um ADR quando a decisão atende a pelo menos um critério:

- afeta mais de um módulo (`mensageria-outbox`, `mensageria-inbox`, `pagamentos`, `extrato`, `reconciliacao`);
- escolhe entre alternativas reais com custos diferentes;
- altera uma convenção global (banco, broker, versão de Java, modelo de transação, formato de envelope);
- seria difícil de reverter ou de entender só pelo código.

Decisão local e óbvia fica em comentário de código ou na mensagem de commit.

### Formato do ADR

```markdown
# NNNN. Título no imperativo curto

- **Status:** Proposto | Aceito | Rejeitado | Substituído por NNNN
- **Data:** AAAA-MM-DD
- **Decisores:** nomes ou papéis

## Contexto
Problema, forças em jogo e restrições (cite spec, requisito RF-A* ou decisão D* de origem).

## Alternativas consideradas
Cada alternativa com vantagens e desvantagens objetivas.

## Decisão
A escolha, em voz ativa ("Adotamos ...").

## Consequências
Efeitos positivos, custos assumidos e o que passa a ser obrigatório.

## Observações da PoC
Somente quando houver medição ou experimento (ver abaixo).
```

### Ciclo de vida

1. Crie o ADR com status `Proposto` ao abrir a discussão.
2. Mude para `Aceito` ou `Rejeitado` quando a decisão for tomada por quem decide, e preencha a data.
3. Um ADR aceito é imutável quanto à decisão. Para mudar o rumo, crie um novo ADR que o substitui, marque o antigo como `Substituído por NNNN` e mantenha o antigo no repositório. Correções de erro de digitação e links quebrados são permitidas.
4. Mantenha as skills e o `AGENTS.md` coerentes com o ADR aceito quando a decisão alterar uma convenção que eles descrevem.

### ADR da estratégia de relay

Arquivo `docs/adr/0001-estrategia-de-relay.md`. Contexto: o valor de `IDENTITY` do SQL Server é atribuído antes do commit, então uma transação com `seq = 10` pode confirmar depois de outra com `seq = 11`, e um relay que lê "tudo maior que o último `seq`" pode pular a linha 10.

Alternativas, ambas já implementadas atrás da interface do relay:

| | Estratégia 1: claim | Estratégia 2: ponto de leitura |
|---|---|---|
| Mecanismo | `SELECT ... WITH (UPDLOCK, READPAST, ROWLOCK)`, publica e marca ou remove a linha | lê até um limite seguro (`rowversion` com `min_active_rowversion()`) e persiste o ponto de leitura |
| Escrita no banco | UPDATE/DELETE por linha | insert-only, limpeza em lote |
| Ordem por agregado | exige cuidado ao reservar lotes | natural, se respeitado o limite seguro |

A seção **Observações da PoC** é preenchida com dados obtidos da execução real, nunca de estimativa, para cada estratégia:

- resultado do cenário A-F7 (commits fora da ordem de `seq`) e dos demais cenários A-F1 a A-F6, verificados pelo reconciliador;
- lag da outbox, mensagens pendentes, duplicatas descartadas, tentativas de retry e tempo de recuperação após falha (métricas do RF-A10), com o ambiente e a versão do SQL Server usados;
- contenção e bloqueios observados no banco de Pagamentos (UPDATE/DELETE por linha versus insert-only);
- comportamento da ordem por `aggregateKey`;
- semântica confirmada de `min_active_rowversion()` na versão do SQL Server usada (o spec marca este ponto para confirmação);
- linhas de código e quantidade de testes necessários para cada estratégia;
- política de limpeza da outbox (RF-A9) e seu efeito sobre cada estratégia.

Nenhum número de desempenho é meta (spec §3 e §9.1): os dados são observação para a decisão, não limite de aprovação. O ADR fica `Proposto` enquanto faltar medição e vira `Aceito` quando a pessoa decisora escolher a estratégia.

## Ferramentas e artefatos

- `docs/adr/*.md` e `docs/adr/README.md` (índice).
- Fontes citadas no contexto: `spec-caso-a-outbox-inbox.md` (§8, §12, §13), `.agents/context/discovery-answers.md` (D4, D5, D8).
- Os dados das observações vêm da suíte de testes de falha e das métricas Micrometer dos módulos `mensageria-outbox` e `mensageria-inbox`.

## Restrições e armadilhas

- Um ADR por decisão: não agrupe decisões independentes.
- Não copie o spec inteiro no ADR; cite a seção e resuma só o que sustenta a decisão.
- Não registre decisão que ninguém tomou. Se a escolha é da pessoa responsável e ainda não foi feita, o status é `Proposto`.
- Não apague nem reescreva a decisão de um ADR aceito; substitua-o.
- Não deixe o índice desatualizado: ADR sem linha no `README.md` é considerado inexistente.
- Descreva o estado atual da decisão; o histórico de como se chegou a ela vive nas alternativas consideradas e nos ADRs substituídos.
