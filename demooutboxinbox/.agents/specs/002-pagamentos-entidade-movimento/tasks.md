# Tarefas: Entidade de movimento

- [x] **T1. Atualizar a documentação autoritativa com a entidade de movimento e a geração de esquema**
  - Depende de: nenhuma
  - Fonte autoritativa: `.agents/skills/pagamentos/SKILL.md`, seção "Entidades e dados".
    - Mudança: incluir a tabela `movimento` com as colunas `id`, `data_hora`, `tipo`, `descricao` e `valor`, os tipos, a obrigatoriedade e os valores `E` (entrada) e `S` (saída) da coluna `tipo` (`CHAR(1)`), sem regra de uso, endpoint nem evento associados.
    - Origem: História 1 da spec e os critérios de aceite "os campos da entidade são mapeados para as colunas da tabela" e "o campo `tipo` é do tipo `char`, com `E` para entrada e `S` para saída".
  - Fonte autoritativa: `.agents/skills/pagamentos/references/technical-dependencies.md`, lista de dependências.
    - Mudança: incluir a geração de esquema do Hibernate (`spring.jpa.hibernate.ddl-auto=update`) como precondição da tabela `movimento`; o item 6, sobre a tabela `pagamento`, continua como está.
    - Origem: História 2 da spec e o critério de aceite "a subida cria a tabela ausente".
  - Fonte autoritativa: `AGENTS.md`, seção "Convenções de arquitetura".
    - Mudança: listar `movimento` no banco único ao lado de `pagamento` e das tabelas da outbox, e registrar que as tabelas das entidades JPA são criadas pelo Hibernate na inicialização com `ddl-auto=update`, sem ferramenta de migração.
    - Origem: Histórias 2 e 3 da spec e o critério de aceite "a reinicialização mantém a tabela e as linhas gravadas".
  - Base: seção "Impacto na documentação autoritativa" do `plan.md`. A skill `integracao-mensageria-outbox-spring-boot` não muda.

- [x] **T2. Entidade `MovimentoEntity` com criação da tabela `movimento` na subida**
  - Depende de: T1
  - Pré-condição: o SQL Server do `../compose.yaml` está no ar e o `../.env` da raiz define `MSSQL_SA_PASSWORD`, para subir a aplicação e rodar o `contextLoads`.
  - Criar a entidade `MovimentoEntity` no subpacote `movimento`, com o mapeamento do `data-model.md`; o tipo da movimentação é um campo `char` da própria entidade, sem classe de enumeração.
  - Configurar a geração de esquema do Hibernate no `application.properties`, como decidido no `plan.md`.
  - A tabela criada pela aplicação não sofre `create table` nem `alter table` nas reinicializações seguintes.
  - Testes: a unidade não cria testes novos, porque a estratégia do projeto é só o `contextLoads`. O `./mvnw test` precisa passar com o ambiente local no ar.
  - Casos de teste impactados: TC-1 a TC-15 do `test-cases.md`.
