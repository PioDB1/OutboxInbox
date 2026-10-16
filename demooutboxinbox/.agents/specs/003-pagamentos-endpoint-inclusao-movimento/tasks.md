# Tarefas: Endpoint de inclusão de movimento

- [x] **T1. Atualizar a documentação autoritativa com o endpoint de inclusão de movimento**
  - Depende de: nenhuma
  - Fonte autoritativa: `.agents/skills/pagamentos/SKILL.md`, seção "Entidades e dados", subseção "Tabela `movimento` (SQL Server)".
    - Mudança: incluir o contrato do `POST /movimentos` (campos obrigatórios `dataHora` em ISO-8601 UTC, `tipo`, `descricao` e `valor`; parâmetro `forcarFalha`, que força uma falha depois do `INSERT` e antes do commit; respostas `201`, `400` e `500`, estas duas sem gravar nada) e a gravação em transação local desfeita em qualquer falha anterior ao commit, mantendo a ausência de evento e de vínculo com `pagamento` e cooperado.
    - Origem: Histórias 1, 2 e 3 da spec e os critérios de aceite "`POST /movimentos` com corpo válido responde `201`", "campo ausente ou `null` responde `400` e nada é gravado" e "`forcarFalha=true` responde `500` e não deixa a linha gravada".
  - Fonte autoritativa: `.agents/skills/pagamentos/SKILL.md`, frontmatter (`description`).
    - Mudança: citar o endpoint `POST /movimentos` entre os assuntos que acionam a skill.
    - Origem: História 1 da spec.
  - Base: seção "Impacto na documentação autoritativa" do `plan.md`. Não mudam `references/technical-dependencies.md`, a skill `integracao-mensageria-outbox-spring-boot` e o `AGENTS.md`.

- [x] **T2. Inclusão de movimentação por `POST /movimentos`**
  - Depende de: T1
  - Pré-condição: o SQL Server do `../compose.yaml` está no ar e o `../.env` da raiz define `MSSQL_SA_PASSWORD`, para subir a aplicação e rodar o `contextLoads`.
  - Expor `POST /movimentos`, que grava a `MovimentoEntity` existente em uma transação gerenciada pelo Spring e responde `201` com a movimentação como o banco a registrou, com o contrato da seção "Fronteira do domínio" do `spec.md` e as decisões do `plan.md`.
  - Recusar com `400`, sem gravar, o corpo com campo obrigatório ausente ou nulo, o corpo ilegível e a `dataHora` fora do formato; as recusas do banco respondem `500` e não deixam linha gravada.
  - Testes: a unidade não cria testes novos, porque a estratégia do projeto é só o `contextLoads`. O `./mvnw test` precisa passar com o ambiente local no ar.
  - Casos de teste impactados: TC-1, TC-2 e TC-4 a TC-13 do `test-cases.md`.

- [x] **T3. Falha forçada após o insert com o parâmetro `forcarFalha`**
  - Depende de: T2
  - Aceitar no `POST /movimentos` o parâmetro opcional `forcarFalha`; com `true`, a inclusão falha depois de o `INSERT` chegar ao banco e antes do commit, a transação é desfeita e a resposta é `500`; com valor não booleano, a resposta é `400` sem gravar.
  - Testes: a unidade não cria testes novos, porque a estratégia do projeto é só o `contextLoads`. O `./mvnw test` precisa passar com o ambiente local no ar.
  - Casos de teste impactados: TC-3 e TC-14 a TC-16 do `test-cases.md`.
