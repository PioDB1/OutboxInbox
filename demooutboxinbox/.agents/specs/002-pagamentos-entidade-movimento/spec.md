# Spec: Entidade de movimento

## Visão geral

Cria a entidade JPA `MovimentoEntity`, que representa uma movimentação de entrada ou saída com data e hora, descrição e valor. A tabela `movimento` correspondente é criada na inicialização da aplicação quando ainda não existe; quando já existe, é mantida com os dados que contém.

## Domínio

- Slug: `pagamentos`
- Skill: [`.agents/skills/pagamentos/SKILL.md`](../../skills/pagamentos/SKILL.md) — seção "Entidades e dados" (convenções de identificadores, valores monetários e instantes) e seção "Integrações e dependências externas" (SQL Server como banco único da aplicação).
- Dependências técnicas: [`.agents/skills/pagamentos/references/technical-dependencies.md`](../../skills/pagamentos/references/technical-dependencies.md), itens 3 e 5.

## Escopo

**Dentro:**

- Registro de movimentação com identificador, data e hora, tipo (entrada ou saída), descrição e valor.
- Criação da estrutura de armazenamento das movimentações na subida da aplicação, só quando ela ainda não existe.
- Preservação das movimentações já gravadas entre reinicializações da aplicação.

**Fora:**

- Inclusão, consulta, alteração ou exclusão de movimentações por API ou tela.
- Vínculo de uma movimentação com pagamento ou cooperado, cálculo de saldo e qualquer regra de negócio sobre movimentações (sinal do valor, limites, data futura).
- Publicação de evento sobre movimentações.
- Criação da tabela `pagamento` e das tabelas da outbox.

## Fronteira do domínio

**O que esta spec implementa:**

- Entidade JPA `MovimentoEntity`, sob o pacote base `br.com.db1.demooutboxinbox`, mapeada para a tabela `movimento`.
- Campo `tipo` da entidade do tipo `char`, com `E` para entrada e `S` para saída.
- Tabela `movimento` no SQL Server, com a estrutura abaixo:

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `BIGINT IDENTITY` | chave primária, número sequencial gerado pelo banco |
| `data_hora` | `DATETIME2` | obrigatória; data e hora da movimentação, instante em UTC |
| `tipo` | `CHAR(1)` | obrigatória; `E` (entrada) ou `S` (saída) |
| `descricao` | `VARCHAR(255)` | obrigatória; até 255 caracteres |
| `valor` | `DECIMAL(18,2)` | obrigatória; duas casas decimais |

- Configuração do Spring Data JPA em `src/main/resources/application.properties` para gerar o esquema das entidades na inicialização: cria a tabela ausente e nunca remove nem recria uma tabela existente.

**Pertence a outros domínios ou unidades (dependência, não vira tarefa aqui):**

- Repositório Spring Data, serviço e endpoint de movimentações: unidades futuras do domínio Pagamentos, quando houver regra de uso definida.
- Tabela `pagamento` e entidade JPA de `pagamento` (item 6 das dependências técnicas): outra unidade do domínio Pagamentos.
- Tabelas `outbox`, `outbox_lease` e `outbox_read_point`: criadas pelo `OutboxSchemaInstaller` da biblioteca `mensageria-outbox`, no adaptador descrito na skill [`integracao-mensageria-outbox-spring-boot`](../../skills/integracao-mensageria-outbox-spring-boot/SKILL.md).
- Ambiente local do `../compose.yaml` (SQL Server e `../.env`): infraestrutura do repositório, consumida como está.

## Histórias de usuário

1. Como desenvolvedor da POC, quero uma entidade de movimento com data e hora, tipo, descrição e valor, para registrar entradas e saídas no banco da aplicação.
2. Como desenvolvedor da POC, quero que a tabela de movimentos seja criada pela própria aplicação ao subir, para usar um banco novo sem executar script manual.
3. Como desenvolvedor da POC, quero que uma tabela de movimentos já existente seja mantida na subida, para não perder as movimentações gravadas.

## Critérios de aceite

**História 1: entidade de movimento**

- Dado o código da aplicação, quando se inspeciona o pacote base `br.com.db1.demooutboxinbox` e os seus subpacotes, então existe a classe `MovimentoEntity` anotada como entidade JPA, mapeada explicitamente para a tabela `movimento`. Sem o nome explícito, a estratégia de nomes do Spring Boot geraria a tabela `movimento_entity`.
- Dado a `MovimentoEntity`, quando se inspecionam os seus campos, então ela tem o identificador `id` gerado pelo banco, a data e hora da movimentação, o tipo, a descrição e o valor, mapeados para as colunas `id`, `data_hora`, `tipo`, `descricao` e `valor`.
- Dado o tipo da movimentação, quando se inspeciona o campo `tipo` da `MovimentoEntity`, então ele é do tipo `char`, sem classe de enumeração associada, e os valores válidos são `E` para entrada e `S` para saída.

**História 2: criação da tabela na subida**

- Dado o SQL Server do `../compose.yaml` no ar, com o banco `master` sem a tabela `movimento`, quando a aplicação é iniciada com `./mvnw spring-boot:run` a partir de `demooutboxinbox/`, então ela sobe sem erro e a tabela `movimento` passa a existir.
- Dado a tabela criada pela aplicação, quando se consulta `INFORMATION_SCHEMA.COLUMNS` para `movimento`, então as colunas são `id` (`bigint`), `data_hora` (`datetime2`), `tipo` (`char`, 1), `descricao` (`varchar`, 255) e `valor` (`decimal`, precisão 18, escala 2), todas com `IS_NULLABLE = 'NO'`.
- Dado a tabela criada pela aplicação, quando se consulta `COLUMNPROPERTY(OBJECT_ID('movimento'), 'id', 'IsIdentity')`, então o resultado é `1`, e `id` é a chave primária da tabela.
- Dado a tabela criada pela aplicação, quando se executa `INSERT INTO movimento (data_hora, tipo, descricao, valor) VALUES (SYSUTCDATETIME(), 'E', 'Teste', 10.50)`, então a linha é gravada com um `id` gerado pelo banco.
- Dado a tabela criada pela aplicação, quando se tenta gravar uma linha com `descricao` nula ou com mais de 255 caracteres, então o banco recusa a gravação.
- Dado a tabela criada pela aplicação, quando se tenta gravar uma linha com `tipo` diferente de `E` e `S`, então o banco recusa a gravação.
- Dado o ambiente local no ar, quando `./mvnw test` é executado a partir de `demooutboxinbox/`, então o teste `contextLoads` passa.

**História 3: tabela existente preservada**

- Dado a tabela `movimento` já existente com ao menos uma linha gravada, quando a aplicação é reiniciada, então ela sobe sem erro, a tabela não é recriada e as linhas gravadas continuam presentes com os mesmos valores.
- Dado o banco `master` com outras tabelas que não são entidades JPA da aplicação, quando a aplicação é iniciada, então essas tabelas não são removidas nem alteradas.

## Comportamento atual → novo

| Aspecto | Atual | Novo |
|---|---|---|
| Entidades JPA | Nenhuma | `MovimentoEntity`, mapeada para `movimento` |
| Geração de esquema pelo Hibernate | Padrão do Spring Boot para banco não embarcado (`none`): nenhuma tabela é criada na subida | A subida cria as tabelas das entidades que ainda não existem e mantém as existentes |
| Banco `master` | Sem tabelas de negócio da aplicação | Contém a tabela `movimento` |

Continuam iguais: a conexão com o SQL Server (`spring.datasource.*` e importação do `../.env`), a verificação de saúde, a ausência de endpoints e do fluxo de pagamento, e o `../compose.yaml`.

## Dependências entre domínios

Nenhuma. O domínio Pagamentos é o único da aplicação.

## Riscos e observações

- A movimentação não aparece na skill de Pagamentos, que descreve só `pagamento`, a outbox e o evento `PagamentoRecebido`. O `AGENTS.md` e a decisão D4 de `.agents/context/discovery-answers.md` registram que o banco contém `pagamento` e as tabelas da outbox, sem ferramenta de migração. Esta unidade acrescenta a tabela `movimento` e a geração de esquema pelo Hibernate na subida, por pedido do requisitante; o impacto na documentação autoritativa é registrado no `plan.md`.
- A geração de esquema do Hibernate vale para todas as entidades da unidade de persistência. Uma entidade de `pagamento` criada depois também teria a tabela gerada pelo Hibernate na subida. A estrutura fixa de `pagamento` na skill inclui `DEFAULT SYSUTCDATETIME()` em `criado_em`, que a unidade dessa entidade precisa reproduzir no mapeamento.
- Com uma tabela `movimento` já existente e estrutura diferente da descrita, a subida acrescenta as colunas ausentes e altera o tipo ou o tamanho das colunas divergentes. No SQL Server, essa alteração deixa a coluna anulável, e ela falha quando os dados gravados não cabem no novo tipo. A tabela criada pela própria aplicação não passa por essa alteração nas reinicializações seguintes.
- A tabela `movimento` fica no banco `master` do SQL Server local, como as demais tabelas do demo. Isso vale para o ambiente de demonstração e não se estende a outros ambientes.
- A entidade não tem uso na aplicação nesta unidade: nenhuma rota, serviço ou repositório grava ou lê movimentações. A gravação nos critérios de aceite é feita direto no banco.
