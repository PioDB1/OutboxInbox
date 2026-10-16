# Tarefas: Conexão com o SQL Server local

- [x] **T1. Atualizar a documentação autoritativa sobre a conexão com o SQL Server**
  - Depende de: nenhuma
  - Fonte autoritativa: `.agents/skills/integracao-mensageria-outbox-spring-boot/SKILL.md`, seção "1. Dependências e configuração".
    - Mudança: registrar que o `application.properties` importa o `../.env` da raiz como fonte de configuração (`spring.config.import=optional:file:../.env[.properties]`) e que as variáveis de ambiente do processo têm precedência sobre esse arquivo.
    - Origem: História 2 da spec e o critério de aceite "senha só no `../.env`, sem a variável exportada no shell".
  - Fonte autoritativa: `.agents/skills/pagamentos/references/technical-dependencies.md`, itens 5 e 7.
    - Mudança: o item 5 passa a descrever o `spring-boot-starter-data-jpa` como declarado no `pom.xml`. O item 7 passa a descrever a conexão com o SQL Server como configurada no `application.properties` e mantém como pendentes a configuração do RabbitMQ e o `outbox.relay.enabled=false` declarado.
    - Origem: História 1 da spec e o critério de aceite "a aplicação sobe e se conecta ao `master` com o login `sa`".
  - Base: seção "Impacto na documentação autoritativa" do `plan.md`. A skill de domínio `.agents/skills/pagamentos/SKILL.md` não muda.

- [x] **T2. Conexão da aplicação com o SQL Server local**
  - Depende de: T1
  - Pré-condição: o SQL Server do `../compose.yaml` está no ar e o `../.env` da raiz define `MSSQL_SA_PASSWORD`, para validar a conexão e rodar o `contextLoads`.
  - Pré-requisito: declarar o `spring-boot-starter-data-jpa` no `pom.xml`. Ele cumpre o item 5 das dependências técnicas do domínio.
  - Configurar o `application.properties` com as decisões do `plan.md`:
    - importação do `../.env`;
    - URL JDBC para o banco `master` em `localhost`, na porta `${MSSQL_PORT:1433}`, aceitando o certificado do contêiner;
    - usuário `sa` e senha `${MSSQL_SA_PASSWORD}`;
    - exposição dos componentes de saúde.
  - Nenhuma senha literal entra em arquivo versionado.
  - Testes: a unidade não cria testes novos, porque a estratégia do projeto é só o `contextLoads`. O `./mvnw test` precisa passar com o ambiente local no ar.
  - Casos de teste impactados: TC-1 a TC-12 do `test-cases.md`.
