# Pesquisa: Mensageria Outbox

Cada decisão registra contexto, alternativas, decisão, evidência e consequências. A evidência vem de leitura dos artefatos do repositório, de consulta ao Maven Central, ao registro de imagens e à documentação da Microsoft, e de inspeção do bytecode dos JARs. Não há Docker na estação onde o plano foi escrito; nenhuma decisão depende de execução contra contêineres.

## Forma de entrega do módulo

**Contexto.** O pedido é "uma aplicação" que implemente o domínio; `AGENTS.md` define `mensageria-outbox/` como módulo "sem Spring Boot".

**Alternativas.** Biblioteca embutida pelo hospedeiro; módulo com adaptador de Spring Boot; executável de relay independente.

**Decisão.** Biblioteca Java 21 embutida pelo hospedeiro, com o relay como componente de ciclo de vida.

**Evidência.** `AGENTS.md` (estrutura e "Pagamentos consome o `mensageria-outbox` ... por adaptador fino", D3) e skill `mensageria-outbox` R21 e R19a ("liga/desliga o relay por instância").

**Consequências.** Sem `main` nem HTTP no módulo; o adaptador Spring Boot e o início do relay ficam em Pagamentos.

## Acesso a banco

**Contexto.** R20 exige isolamento por dialeto; o módulo não usa framework.

**Alternativas.** JDBC puro; biblioteca de acesso a dados (jOOQ, JDBI); JPA.

**Decisão.** JDBC puro sobre `DataSource`, com SQL confinado a `dialect.sqlserver`.

**Evidência.** A skill lista apenas o "driver JDBC do SQL Server" como dependência (`references/technical-dependencies.md`); nenhuma biblioteca de acesso a dados é citada pelas fontes, e as consultas usam dicas de bloqueio e funções do SQL Server que uma camada de mapeamento não expressa.

**Consequências.** Uma única dependência de banco (`mssql-jdbc`); um segundo banco exige uma nova implementação de `OutboxDialect`.

## Formato da mensagem no broker

**Contexto.** Os módulos outbox e inbox têm envelopes próprios; o formato no broker é o contrato entre eles. O módulo transporta o payload sem interpretá-lo (R3, skill "Fronteira").

**Alternativas.** (a) corpo com os bytes do payload e campos do envelope em properties e headers; (b) corpo com o envelope em JSON e payload aninhado como objeto JSON.

**Decisão.** (a), por decisão humana desta spec.

**Evidência.** Decisão humana registrada na rodada de lacunas do plano.

**Consequências.** O módulo não depende de biblioteca JSON; o payload permanece opaco; a skill `mensageria-inbox` lê o envelope dos headers (ver "Impacto na documentação autoritativa" no `plan.md`). Especificação em [`contracts/mensagem-publicada.md`](./contracts/mensagem-publicada.md).

## Cliente RabbitMQ e semântica de confirm

**Contexto.** R22 isola o broker em um adaptador; o módulo não pode usar Spring AMQP (R21). Um confirm positivo do broker chega também para mensagem sem fila ligada, que o broker descarta.

**Alternativas.** `com.rabbitmq:amqp-client` 0-9-1; `rabbitmq-amqp-client` (AMQP 1.0).

**Decisão.** `com.rabbitmq:amqp-client` 5.37.0, com `confirmSelect`, `waitForConfirms(timeout)` por mensagem e `mandatory=true` com `ReturnListener`.

**Evidência.** Decisão humana; Maven Central lista 5.37.0 como `release`; o bytecode do JAR tem major 52 (Java 8), executável em Java 21. A combinação `mandatory` + `ReturnListener` converte o descarte silencioso em falha de publicação, preservando R9 e R13 mesmo quando a fila do consumidor ainda não existe.

**Consequências.** Publicação sequencial com um confirm por mensagem; nenhum número de desempenho é meta (R27).

## Logs estruturados

**Contexto.** D7 e R25 exigem logs com `messageId`, `correlationId`, `aggregateKey` e `traceId` sem framework no módulo.

**Alternativas.** SLF4J API com MDC; `java.lang.System.Logger`.

**Decisão.** SLF4J API 2.0.20 com MDC, definido e removido em volta de cada tentativa de publicação.

**Evidência.** Decisão humana; Maven Central lista a série 2.0.x (2.0.20); bytecode major 52. Logback 1.5.38 (pom: `slf4j.version` 2.0.17, `jdk.version` 11) em escopo de teste captura o MDC com `ListAppender`.

**Consequências.** O hospedeiro escolhe o backend e o formato (JSON) dos logs.

## Versão do SQL Server e semântica de `min_active_rowversion()`

**Contexto.** R17 exige confirmar e registrar a semântica na versão usada.

**Alternativas.** SQL Server 2019; 2022.

**Decisão.** SQL Server 2019, tag `2019-CU32-ubuntu-20.04`.

**Evidência.** Decisão humana; a tag `2019-CU32-ubuntu-20.04` consta da lista de tags de `mcr.microsoft.com/v2/mssql/server`. A documentação da Microsoft (`MIN_ACTIVE_ROWVERSION`, repositório `MicrosoftDocs/sql-docs`) define: devolve o menor `rowversion` ativo no banco corrente, ativo é o valor usado em transação ainda não confirmada; sem valores ativos devolve `@@DBTS + 1`; quem usa `@@DBTS` em vez de `MIN_ACTIVE_ROWVERSION` pode perder alterações ainda ativas durante a sincronização; a função não é afetada pelo nível de isolamento. Essa definição sustenta a estratégia de ponto de leitura; o teste de integração do módulo registra o comportamento observado na imagem fixada no ADR `0001`.

**Consequências.** A leitura do limite seguro ocorre sempre antes da leitura das linhas; o limite vale para o banco inteiro e pode ser mais baixo que o necessário, o que só atrasa a publicação.

## Estratégia 1: claim

**Contexto.** `READPAST` ignora linhas bloqueadas por transações abertas. Se T1 (`seq` 10, aggregate X) está aberta e T2 (`seq` 11, aggregate X) confirmou, a seleção devolve a linha 11 e a publica antes da 10, violando R11.

**Alternativas.** Publicar tudo que a seleção devolve; sonda por linha anterior do mesmo `aggregateKey`; `TABLOCK` no ciclo.

**Decisão.** Sonda por linha anterior: após reservar o lote (`UPDLOCK, READPAST, ROWLOCK`, ordenado por `seq`), cada candidato é publicado somente se não existir linha do mesmo `aggregate_key` com `seq` menor e `sent_at IS NULL` fora do lote reservado. A sonda usa `READUNCOMMITTED` para enxergar linhas ainda não confirmadas ou reservadas por outra instância. Candidatos bloqueados pela sonda ou por falha anterior no lote ficam sem publicar e seguem pendentes. O ciclo publica em ordem, marca `sent_at` das linhas confirmadas e faz commit.

**Evidência.** `READUNCOMMITTED` lê linhas com bloqueio exclusivo de outras transações (comportamento documentado do nível de isolamento), o que torna visível a linha 10 do exemplo. O índice `IX_outbox_aggregate` cobre a sonda. O teste de integração de commit fora de ordem com o mesmo `aggregateKey` é a verificação executável (critério da história 3 da spec).

**Consequências.** Publicar dentro de uma transação que mantém bloqueios sobre as linhas reservadas; queda do relay antes do commit republica o lote (R10). Uma linha cuja transação foi revertida deixa de bloquear a sonda no ciclo seguinte.

## Estratégia 2: ponto de leitura

**Contexto.** A estratégia somente insere na outbox e persiste o ponto de leitura.

**Decisão.** Por ciclo: (1) `limite = MIN_ACTIVE_ROWVERSION()`; (2) `ponto = outbox_read_point.last_rv`; (3) `SELECT TOP (lote) ... WHERE rv > ponto AND rv < limite ORDER BY rv`; (4) publicação em ordem; (5) `last_rv` avança até o `rv` da última linha do prefixo contíguo publicado. Em falha de uma linha, as demais linhas do mesmo `aggregateKey` do lote ficam sem publicar; as linhas de outros `aggregateKey` seguem e o ponto fica antes da primeira linha com falha. As linhas publicadas à frente do ponto são lembradas em um conjunto em memória, limitado ao tamanho do lote, e não são republicadas enquanto o processo vive.

**Evidência.** Definição de `MIN_ACTIVE_ROWVERSION()` acima: uma linha cujo `rv` é menor que o limite pertence a transação já terminada, e duas inserções do mesmo `aggregateKey` só entram na faixa lida depois que ambas confirmam, o que preserva a ordem por `rv`.

**Consequências.** Com uma linha persistentemente falha, apenas a janela de `lote` linhas após o ponto é atendida; após reinício, as linhas publicadas à frente do ponto são republicadas (at-least-once). O índice único `UX_outbox_rv` sustenta a leitura por faixa.

## Lease

**Contexto.** R18 e R19: no máximo um relay ativo, lease com expiração no próprio banco; segurança não depende de lease perfeito.

**Alternativas.** `sp_getapplock`; linha de lease com `UPDATE` condicional; relógio da aplicação.

**Decisão.** Linha única em `outbox_lease`; aquisição e renovação por `UPDATE ... SET holder_id=?, expires_at=DATEADD(..., SYSUTCDATETIME()) WHERE lease_name='relay' AND (holder_id=? OR expires_at < SYSUTCDATETIME())`; o relay só executa um ciclo após adquirir ou renovar o lease e renova a cada `lease.renew-interval`. O relógio é o do banco, comum às instâncias.

**Evidência.** A skill `mensageria-outbox` exige o lease "guardado no próprio banco de origem" com expiração (R18) e aceita duplicidade por falha do lease (R19), o que dispensa token de fencing; `sp_getapplock` prende a sessão e não expira por tempo.

**Consequências.** Duração 15 s e renovação 5 s; uma instância congelada por mais de 15 s perde o lease e, ao acordar, pode publicar um ciclo concorrente (A-F5), absorvido pelo consumidor.

## Backoff e falhas de publicação

**Decisão.** `BackoffPolicy` exponencial determinístico: `min(max, inicial × fator^(tentativa-1))`; estado por `aggregateKey` em mapa limitado pelo lote, removido no sucesso. Um `aggregateKey` em backoff é ignorado até `proximaTentativa`; os demais seguem. Falha de conexão ou confirm negativo de uma mensagem encerra o lote naquele ponto. O contador `outbox.publish.retries` conta cada nova tentativa de uma linha que já falhou.

**Evidência.** R11 e R12 da skill (não publicar mensagem posterior do mesmo `aggregateKey` enquanto uma anterior aguarda nova tentativa; intervalos crescentes). Sem jitter para manter o critério "intervalo não decrescente" da spec verificável.

**Consequências.** Nenhuma linha é descartada por falha; o relay mantém no máximo `lote` chaves em backoff.

## Retenção

**Decisão.** Rotina `OutboxRetention` executada pelo relay ativo a cada `retention.interval`: claim apaga `DELETE TOP (n) ... WHERE sent_at < agora - max-age`; ponto de leitura apaga `DELETE TOP (n) ... WHERE rv <= last_rv AND created_at < agora - max-age`, em lotes de `retention.batch-size`. Nenhuma das duas toca linha pendente.

**Evidência.** R23: remove somente linhas publicadas com confirm; no ponto de leitura as linhas com `rv <= last_rv` já foram ultrapassadas e o limite seguro depende do `rv` persistido, não da existência da linha.

## Métricas

**Decisão.** `OutboxMetrics` registra os gauges lendo valores atualizados a cada ciclo por `PendingQuery`: `count(*)` e `DATEDIFF_BIG(MILLISECOND, MIN(created_at), SYSUTCDATETIME())/1000.0` sobre as linhas pendentes da estratégia, sem ler `payload`.

**Evidência.** R24 fixa as três grandezas; a spec define os nomes e o lag como idade da pendente mais antiga.

## Memória

**Decisão.** Leituras sempre com `TOP (batch-size)`, `payload` lido apenas das linhas do lote; mapas e conjuntos em memória limitados ao lote; thread única de relay. O teste de carga usa 10 000 linhas (100 lotes), `-Xmx256m -XX:MaxDirectMemorySize=64m` no `argLine` do failsafe, três pontos de medição do heap após `System.gc()` e falha se o heap crescer mais de 32 MiB entre o segundo e o terceiro ponto.

**Evidência.** R26: lotes de tamanho limitado e limites explícitos de heap e de memória direta.

## Versões das dependências

**Contexto.** Java 21 é a versão mínima (D1); D6 pede a confirmação de compatibilidade de Testcontainers e Micrometer.

**Evidência.** Versões `release` no Maven Central; major do bytecode lido diretamente dos JARs (Java 21 executa até major 65):

| Artefato | Versão | Major do bytecode |
|---|---|---|
| `com.rabbitmq:amqp-client` | 5.37.0 | 52 |
| `com.microsoft.sqlserver:mssql-jdbc` | 12.10.2.jre11 | 55 |
| `io.micrometer:micrometer-core` | 1.17.1 | 52 |
| `org.slf4j:slf4j-api` | 2.0.20 | 52 |
| `org.testcontainers:testcontainers`, `testcontainers-mssqlserver`, `testcontainers-rabbitmq`, `testcontainers-toxiproxy` | 2.0.5 | 52 |
| `eu.rekawek.toxiproxy:toxiproxy-java` (transitivo do módulo Toxiproxy) | 2.1.11 | 52 |
| `ch.qos.logback:logback-classic` (teste) | 1.5.38 | pom: `jdk.version` 11 |
| `org.junit:junit-bom` (`junit-jupiter`) | 5.14.4 | série 5 citada pela skill `testes-de-falha-resiliencia` |

Plugins: `maven-compiler-plugin` 3.15.0, `maven-surefire-plugin` e `maven-failsafe-plugin` 3.6.0, `maven-enforcer-plugin` 3.6.3.

**Decisão.** Essas versões, gerenciadas no `dependencyManagement` e `pluginManagement` do `pom.xml` raiz (`junit-bom` e `testcontainers-bom` importados). `mssql-jdbc` entra em escopo `runtime`; `amqp-client`, `micrometer-core` e `slf4j-api` em escopo de compilação.

**Consequências.** Testcontainers 2.x nomeia os módulos com o prefixo `testcontainers-`; as classes de teste usam os pacotes da série 2.x.

## Ambiente Docker

**Decisão.** Imagens fixas em `compose.yaml` e em `Images` (testes): `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04`, `rabbitmq:4.3.6-management`, `ghcr.io/shopify/toxiproxy:2.10.0`. O `healthcheck` do SQL Server usa `/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$$MSSQL_SA_PASSWORD" -Q "SELECT 1"`.

**Evidência.** Tags `4.3.6-management` (Docker Hub, repositório `library/rabbitmq`) e `2.10.0` (`ghcr.io/shopify/toxiproxy`) constam das listas de tags dos registros; a página de início rápido do SQL Server 2019 da Microsoft (`view=sql-server-ver15`) cita `/opt/mssql-tools/bin/sqlcmd -S localhost -U <usuário> -P "<senha>"`, sem `-C`.

**Consequências.** Um teste unitário compara as tags de `compose.yaml` com `Images`; o ADR `0001` registra a tag do SQL Server usada nas observações.
