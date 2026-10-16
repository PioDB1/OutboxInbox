# AGENTS.md

Instruções canônicas para agentes de código neste repositório. Este arquivo é a única fonte de verdade global; `CLAUDE.md` e `.github/copilot-instructions.md` apenas apontam para cá.

## Visão geral do projeto

PoC (greenfield) que valida o padrão **outbox/inbox** como substituto da coordenação XA (transaction manager + SQL Server + ActiveMQ) por transações locais + RabbitMQ.

Pergunta que a PoC responde: produtor e consumidor, cada um com seu banco, mantêm consistência eventual correta (sem perda e sem efeito duplicado) sob falhas, sem transação distribuída?

Domínios (detalhes em `.agents/maps/functional-map.md`):

- **`mensageria-outbox`** — módulo do lado produtor: envelope, outbox, relay (duas estratégias atrás de uma única interface), lease de relay único, publisher confirms, retry/backoff de publicação, retenção e métricas de publicação.
- **`mensageria-inbox`** — módulo do lado consumidor: envelope, consumidor com ack manual, inbox, dedup por `messageId`, serialização por `aggregateKey`, retry/backoff, DLQ e métricas de consumo.
- **Pagamentos** — registra o pagamento e publica `PagamentoRecebido` (pagamento e linha da outbox na mesma transação local).
- **Extrato** — consome `PagamentoRecebido` e grava o lançamento previsto (lançamento e inbox na mesma transação local; ack só após o commit).
- **Reconciliação** — job independente, somente leitura, que verifica as invariantes A1 a A4 entre os dois bancos.

Fora de escopo: coordenação de vários passos com compensação (Caso B), CDC/Debezium, ponte ActiveMQ ↔ RabbitMQ, replicação/sincronização de bancos e qualquer meta de latência ou throughput.

Documento de entrada do negócio: `spec-caso-a-outbox-inbox.md` (raiz). Contexto e decisões da descoberta: `.agents/context/`.

## Stack principal

- Java 21 (versão mínima suportada) nos módulos `mensageria-inbox`, `pagamentos`, `extrato` e `reconciliacao`; o `mensageria-outbox` tem Java 8 (bytecode e API) como piso, e a sua suíte é verificada nos JDKs 8, 11, 17, 21 e 25; os módulos `mensageria-outbox-spring-boot3` (Spring Boot 3.x) e `mensageria-outbox-spring-boot4` (Spring Boot 4.x) têm Java 17 como piso (`--release 17`). O build do repositório roda em JDK 21.
- Maven multi-módulo.
- Spring Boot 3.x nas aplicações Pagamentos e Extrato; Pagamentos consome o `mensageria-outbox` e Extrato consome o `mensageria-inbox`, cada um por adaptador fino.
- RabbitMQ com filas quorum, publisher confirms e ack manual no consumidor.
- Bancos: SQL Server (executado em Docker) para o banco de Pagamentos e o relay; H2 salvo em arquivo no banco de Extrato (tabelas `extrato_lancamento` e `inbox`).
- Micrometer para métricas.

## Estrutura do repositório

Estrutura-alvo, um módulo Maven por domínio na raiz:

```
pom.xml              agregador Maven (Java 21 nos módulos, exceto o mensageria-outbox e os mensageria-outbox-spring-boot*; build em JDK 21; dependencyManagement, pluginManagement, enforcer)
compose.yaml         SQL Server, RabbitMQ e Toxiproxy para uso local
.env.example         variáveis do compose.yaml (o .env local não é versionado)
docker/              configuração do Toxiproxy usada pelo compose.yaml
docs/adr/            ADRs e índice
mensageria-outbox/   lado produtor, sem Spring Boot, Java 8 como piso (implementado)
mensageria-outbox-spring-boot3/        auto-configuração do mensageria-outbox para Spring Boot 3.x, Java 17 como piso (implementado)
mensageria-outbox-spring-boot4/        auto-configuração do mensageria-outbox para Spring Boot 4.x, Java 17 como piso (implementado)
mensageria-outbox-spring-boot-shared/  código-fonte comum aos dois módulos acima; não é módulo Maven
mensageria-inbox/    lado consumidor, sem Spring Boot
pagamentos/          aplicação Spring Boot (produtor)
extrato/             aplicação Spring Boot (consumidor)
reconciliacao/       job de reconciliação
.agents/             contexto, mapas e skills para agentes
```

Os módulos `mensageria-inbox`, `pagamentos`, `extrato` e `reconciliacao` ainda não existem. O `README.md` e o workflow `.github/workflows/gradle.yml` herdados de template descrevem Gradle e JDK 8/11, que não correspondem à stack acima; não os use como fonte de build ou de versão. Os READMEs dos módulos implementados estão em `mensageria-outbox/README.md`, `mensageria-outbox-spring-boot3/README.md`, `mensageria-outbox-spring-boot4/README.md` e `mensageria-outbox-spring-boot-shared/README.md`.

## Convenções arquiteturais

- Um banco/schema separado por aplicação; não existe transação local entre Pagamentos e Extrato.
- Sem XA, sem JTA, sem transaction manager externo.
- `mensageria-outbox` e `mensageria-inbox` são independentes entre si e não dependem de Spring Boot; cada um carrega a própria definição do envelope padrão, e cada framework entra por um adaptador fino.
- No lado produtor, o Spring Boot entra pelos módulos `mensageria-outbox-spring-boot3` e `mensageria-outbox-spring-boot4`, um artefato por linha do Spring Boot, com as dependências do Spring como `provided` (vale a versão do Spring Boot da aplicação). O código comum aos dois fica em `mensageria-outbox-spring-boot-shared/`, incluído nas fontes de ambos pelo `build-helper-maven-plugin`; cada módulo guarda só `OutboxRabbitConnectionFactoryConfiguration` e o arquivo `AutoConfiguration.imports`, e uma alteração no código comum é compilada e testada nos dois. A auto-configuração publica cada mensagem após o commit, com ordem por `aggregateKey`, e recupera as pendentes na inicialização, sem o relay por polling (estratégias, lease, retenção, métricas); a aplicação que precisa do relay usa o da biblioteca, e nunca os dois juntos, porque competiriam pelas mesmas linhas pendentes.
- Cada módulo conhece o RabbitMQ apenas por uma interface (publicação no outbox, consumo no inbox); trocar de broker exige só um novo adaptador.
- Tabelas e acessos de outbox, inbox e lease são isolados por dialeto de banco: SQL Server para a outbox e o relay de Pagamentos (as duas estratégias de relay usam `UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion` com `min_active_rowversion()`, ausentes no H2); H2 em arquivo onde esses recursos não são necessários (inbox de Extrato).
- As duas estratégias de relay (claim e ponto de leitura) são implementadas atrás de uma única interface.
- Publicação at-least-once; ordem preservada por `aggregateKey`, sem exigência de ordem global; dedup por `messageId` na inbox.
- O relay é reiniciável a qualquer momento sem intervenção manual, e relay e consumidor não crescem em memória sem limite (limites explícitos de heap e de memória direta).
- Logs estruturados com `messageId`, `correlationId` e `aggregateKey`; `correlationId` e `traceId` propagados nos headers e nos logs; métricas via Micrometer.

## Restrições globais

- Nenhum número de desempenho é meta; as métricas são observação.
- Credenciais dos contêineres e de bancos ficam em variáveis de ambiente ou `.env`, nunca versionadas.
- O banco H2 de cada aplicação é um arquivo próprio, separado dos demais.

## Comandos essenciais

Build Maven multi-módulo, a partir da raiz (não há wrapper `mvnw` nem plugin de lint declarado):

```
mvn clean verify                 # compila e executa os testes de todos os módulos
mvn -pl <módulo> -am test        # testa um módulo e suas dependências
mvn -pl mensageria-outbox verify -Djvm=<JDK_HOME>/bin/java   # suíte do mensageria-outbox em um JDK da matriz (8, 11, 17, 21, 25)
mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install   # instala no repositório Maven local os dois artefatos Spring Boot e o mensageria-outbox
```

O Maven e o compilador rodam em JDK 21; `-Djvm` troca só a JVM em que surefire e failsafe executam os testes.

Convenção de testes: `*Test` (surefire, sem Docker) e `*IT` (failsafe, com Testcontainers; sem Docker em execução eles são ignorados). O failsafe roda com `-Xmx256m -XX:MaxDirectMemorySize=64m`.

Ambiente local para executar as aplicações e inspecionar filas e tabelas:

```
cp .env.example .env             # ajuste as credenciais
docker compose up -d --wait      # sqlserver, rabbitmq e toxiproxy saudáveis
docker compose down              # derruba, preservando os volumes
```

## Variáveis de ambiente

`.env.example` (raiz) declara as variáveis lidas pelo `compose.yaml`; o `.env` local, criado a partir dele, não é versionado.

| Variável | Uso |
|---|---|
| `MSSQL_SA_PASSWORD` | senha do `sa` do SQL Server (mínimo de 8 caracteres, três das quatro classes) |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` | credenciais iniciais do RabbitMQ |
| `MSSQL_PORT`, `RABBITMQ_AMQP_PORT`, `RABBITMQ_MGMT_PORT`, `TOXIPROXY_API_PORT`, `TOXIPROXY_RABBITMQ_PORT` | portas publicadas no host |

Os testes `*IT` usam as variáveis `MSSQL_SA_PASSWORD`, `RABBITMQ_DEFAULT_USER` e `RABBITMQ_DEFAULT_PASS` quando definidas; do contrário geram credenciais efêmeras.

## Estrutura de `.agents/`

- `context/` — memória da descoberta funcional: `business-input.md` (material de negócio), `discovery-answers.md` (restrições e decisões validadas).
- `maps/` — `functional-map.md`, o mapa dos domínios, fronteiras, dependências e ordem de implementação.
- `skills/` — skills carregadas sob demanda pelo agente, guiadas pelo `description` do frontmatter de cada uma. Reúne skills de **negócio** (por domínio) e skills **técnicas** (transversais).

## Skills: precedência e documentação de domínio

- Antes de criar ou editar arquivos, ou de implementar qualquer funcionalidade, verifique se existe skill em `.agents/skills/` que governe o caso — pela tecnologia, padrão de código ou arquitetura envolvidos, ou pelo domínio de negócio, funcionalidade ou módulo em questão — e siga-a antes de agir.
- As skills de `.agents/skills/` são autoritativas e têm precedência sobre padrões inferidos do código existente.
- O corpo de cada skill indica onde vive a documentação completa do seu assunto e qual fonte detém a verdade, dentro ou fora do repositório; siga essa indicação a partir da skill.
- Mantenha as skills atualizadas quando o comportamento que elas descrevem mudar.

## Escrita de comentários e documentação

Todo texto que vive neste repositório — comentário de código, docstring, skill, `AGENTS.md`, README, spec — descreve o estado atual, escrito para quem abre o arquivo hoje sem conhecer a história do arquivo nem a conversa que o produziu.

- **Descreva o que é, nunca a transição.** Ao editar um texto existente, reescreva o trecho a partir do resultado final, como se sempre tivesse sido assim. Uma frase que só faz sentido para quem viu a versão anterior — ou o seu diff — não pertence ao arquivo: o que mudou pertence à mensagem de commit e à descrição do pull request.
- **Negue apenas para prevenir um erro plausível.** Uma negação se justifica quando um leitor competente tentaria de fato a alternativa e a frase explica por que ela falha, protegendo uma mudança futura. Contraste com a versão anterior, com uma alternativa que ninguém tentaria ou com o que o código já mostra é ruído que custa atenção do leitor.
- **Faça a poda antes de concluir a tarefa.** Releia os textos que você criou ou alterou e corte o que não passa nas duas regras acima. Formulações como "não mais", "antes", "agora", "em vez de", "ao invés de", "diferente de" e "sem precisar de" são os sintomas usuais — mantenha só as que sobrevivem à segunda regra.

Uma exceção: quando o assunto do texto **é** uma mudança — mensagem de commit, descrição de pull request, spec de uma unidade de manutenção, changelog — a transição é o conteúdo. A regra proíbe narrar a *edição do texto*, nunca a mudança sobre a qual o texto trata.
