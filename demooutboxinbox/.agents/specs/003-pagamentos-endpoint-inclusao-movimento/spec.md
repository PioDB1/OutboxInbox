# Spec: Endpoint de inclusão de movimento

## Visão geral

Cria o endpoint `POST /movimentos`, que grava uma movimentação na tabela `movimento` pela `MovimentoEntity` em uma transação gerenciada pelo Spring, com um parâmetro de teste que força uma falha depois do insert e antes do commit para demonstrar o rollback.

## Domínio

- Slug: `pagamentos`
- Skill: [`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md) — seção "Entidades e dados", subseção "Tabela `movimento` (SQL Server)" (colunas, tipos e regras da movimentação), e seção "Regras de negócio", regras 2 e 7 (validação de presença e resposta do `POST /pagamentos`, que servem de convenção para o endpoint de movimentos).
- Skill técnica: [`.agents/skills/integracao-mensageria-outbox-spring-boot/SKILL.md`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md) — seção "Gravação na transação JPA" (transação declarativa do `JpaTransactionManager`, com commit e rollback feitos pelo Spring).

## Escopo

**Dentro:**

- Inclusão de uma movimentação por API, informando data e hora, tipo (entrada ou saída), descrição e valor.
- Recusa da inclusão quando falta algum dado obrigatório ou o corpo é ilegível, sem gravar nada.
- Resposta com a movimentação gravada, incluindo o identificador gerado pelo banco.
- Falha forçada sob demanda, depois da gravação e antes da confirmação, para observar que a transação é desfeita e nada permanece gravado.

**Fora:**

- Consulta, alteração e exclusão de movimentações.
- Vínculo de uma movimentação com pagamento ou cooperado, cálculo de saldo e regras sobre o valor (sinal, limites) ou sobre a data (data futura).
- Publicação de evento sobre movimentações e gravação de mensagem na outbox.
- Transação aberta, confirmada e desfeita explicitamente pelo código do endpoint.
- Fluxo de pagamento (`POST /pagamentos`, tabela `pagamento`, outbox e publicação de `PagamentoRecebido`).

## Fronteira do domínio

**O que esta spec implementa:**

- Endpoint `POST /movimentos`, com corpo JSON e parâmetro de consulta opcional `forcarFalha`.
- Repositório Spring Data JPA da `MovimentoEntity`, no pacote `br.com.db1.demooutboxinbox.movimento`.
- Serviço de inclusão de movimento, com a gravação e a falha forçada dentro da mesma transação declarativa do Spring.
- Contrato de requisição e de resposta do endpoint:

Requisição — corpo JSON:

| Campo | Tipo JSON | Regra |
|---|---|---|
| `dataHora` | string | obrigatória; instante ISO-8601 em UTC (por exemplo `2026-10-16T13:45:00Z`) |
| `tipo` | string | obrigatório; um caractere, `E` (entrada) ou `S` (saída) |
| `descricao` | string | obrigatória; até 255 caracteres |
| `valor` | número decimal | obrigatório; registrado com duas casas decimais |

Parâmetro de consulta:

| Parâmetro | Tipo | Regra |
|---|---|---|
| `forcarFalha` | booleano | opcional, padrão `false`; com `true`, a aplicação lança uma falha depois de o `INSERT` chegar ao banco e antes do commit |

Respostas:

| Status | Quando | Corpo |
|---|---|---|
| `201 Created` | movimentação gravada e transação confirmada | a movimentação gravada (tabela abaixo) |
| `400 Bad Request` | `dataHora`, `tipo`, `descricao` ou `valor` ausente ou nulo, corpo ilegível ou `forcarFalha` com valor que não é booleano | nada é gravado |
| `500 Internal Server Error` | falha forçada por `forcarFalha=true`, ou banco recusa a gravação (`tipo` diferente de `E` e `S`, `descricao` acima de 255 caracteres, `valor` que não cabe em `DECIMAL(18,2)`) | nada é gravado |

Corpo da resposta `201`:

| Campo | Conteúdo |
|---|---|
| `id` | identificador numérico gerado pelo banco |
| `dataHora` | o `data_hora` registrado, instante ISO-8601 em UTC |
| `tipo` | `E` ou `S` |
| `descricao` | a descrição registrada |
| `valor` | o valor registrado, com duas casas decimais |

**Pertence a outros domínios ou unidades (dependência, não vira tarefa aqui):**

- Entidade `MovimentoEntity` e tabela `movimento`: existentes, criadas pela unidade `002-pagamentos-entidade-movimento` e consumidas como estão.
- Endpoint `POST /pagamentos`, tabela `pagamento`, outbox e publicação de `PagamentoRecebido`: outras unidades do domínio Pagamentos, sobre o adaptador descrito na skill [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md).
- Conexão com o SQL Server e geração de esquema pelo Hibernate (`spring.datasource.*`, `spring.jpa.hibernate.ddl-auto=update`): configuração existente em `src/main/resources/application.properties`.
- Ambiente local do `../compose.yaml` (SQL Server e `../.env`): infraestrutura do repositório, consumida como está.

## Histórias de usuário

1. Como desenvolvedor da POC, quero incluir uma movimentação por uma chamada HTTP, para gravar entradas e saídas na tabela `movimento` sem escrever SQL.
2. Como desenvolvedor da POC, quero que uma inclusão com dados obrigatórios faltando seja recusada sem gravar nada, para não deixar linhas incompletas.
3. Como desenvolvedor da POC, quero forçar uma falha depois do insert e antes do commit, para ver o efeito do rollback da transação gerenciada pelo Spring sobre uma gravação que já chegou ao banco.

## Critérios de aceite

Os exemplos usam a aplicação iniciada com `./mvnw spring-boot:run` a partir de `demooutboxinbox/`, na porta `8081`, com o SQL Server do `../compose.yaml` no ar.

**História 1: inclusão de movimentação**

- Dado a aplicação no ar, quando se envia `POST /movimentos` com o corpo `{"dataHora":"2026-10-16T13:45:00Z","tipo":"E","descricao":"Depósito","valor":150.00}`, então a API responde `201` com `id` numérico, `dataHora` `2026-10-16T13:45:00Z`, `tipo` `E`, `descricao` `Depósito` e `valor` `150.00`.
- Dado a inclusão anterior, quando se consulta `SELECT * FROM movimento WHERE id = <id da resposta>`, então existe uma linha com `data_hora` no instante `2026-10-16 13:45:00` UTC, `tipo` `E`, `descricao` `Depósito` e `valor` `150.00`.
- Dado a aplicação no ar, quando se envia `POST /movimentos?forcarFalha=false` com um corpo válido de `tipo` `S`, então a API responde `201` e a linha é gravada com `tipo` `S`.
- Dado o ambiente local no ar, quando `./mvnw test` é executado a partir de `demooutboxinbox/`, então o teste `contextLoads` passa.

**História 2: recusa de inclusão incompleta**

- Dado a aplicação no ar, quando se envia `POST /movimentos` sem algum dos campos `dataHora`, `tipo`, `descricao` ou `valor`, ou com algum deles `null`, então a API responde `400` e a contagem de linhas de `movimento` não muda.
- Dado a aplicação no ar, quando se envia `POST /movimentos` com um corpo que não é JSON válido, ou com `dataHora` que não é um instante ISO-8601, então a API responde `400` e nada é gravado.
- Dado a aplicação no ar, quando se envia `POST /movimentos` com `tipo` `X` ou com `descricao` de 256 caracteres, então o banco recusa a gravação, a API responde `500` e nada é gravado.

**História 3: falha forçada e rollback**

- Dado a aplicação no ar, quando se envia `POST /movimentos?forcarFalha=true` com um corpo válido, então a API responde `500` e não existe em `movimento` nenhuma linha com a descrição enviada.
- Dado uma inclusão com sucesso de `id` `N`, seguida de uma inclusão com `forcarFalha=true` e de outra inclusão com sucesso, então a última inclusão recebe um `id` maior que `N + 1`: o valor de identidade consumido pelo `INSERT` desfeito não é devolvido pelo SQL Server, o que comprova que a linha chegou ao banco antes do rollback.
- Dado a aplicação no ar, quando se envia `POST /movimentos?forcarFalha=talvez` com um corpo válido, então a API responde `400` e nada é gravado.

## Comportamento atual → novo

| Aspecto | Atual | Novo |
|---|---|---|
| Endpoints de negócio | Nenhum | `POST /movimentos` |
| Uso da `MovimentoEntity` | Nenhum: nenhuma rota, serviço ou repositório grava ou lê movimentações | Gravada pelo serviço de inclusão de movimento, por meio do repositório Spring Data |
| Transações de negócio na aplicação | Nenhuma | A inclusão de movimento roda em uma transação declarativa do Spring, confirmada ao final e desfeita em qualquer falha anterior ao commit |

Continuam iguais: a `MovimentoEntity` e a estrutura da tabela `movimento`, a conexão com o SQL Server e a geração de esquema pelo Hibernate, a verificação de saúde, a ausência do fluxo de pagamento e da outbox, e o `../compose.yaml`.

## Dependências entre domínios

Nenhuma. O domínio Pagamentos é o único da aplicação.

## Riscos e observações

- A skill de Pagamentos registra que o domínio não define regra de uso, endpoint nem evento para movimentações. Esta unidade acrescenta o endpoint `POST /movimentos` e o parâmetro de teste `forcarFalha`, por pedido do requisitante; o impacto na documentação autoritativa é registrado no `plan.md`.
- O parâmetro `forcarFalha` é um recurso de demonstração: qualquer chamador que o envie com `true` provoca um `500`. A aplicação é uma POC local, sem autenticação.
- O `500` das falhas de gravação e da falha forçada vem do tratamento padrão de erros do Spring Boot, sem corpo de erro próprio. A skill de Pagamentos segue a mesma convenção para o `POST /pagamentos`, cujas falhas de gravação não têm status específico.
- Um `valor` com mais de duas casas decimais é arredondado pelo SQL Server na coluna `DECIMAL(18,2)`. A resposta reflete o valor registrado, que pode diferir do valor recebido.
