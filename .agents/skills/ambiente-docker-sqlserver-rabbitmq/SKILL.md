---
name: ambiente-docker-sqlserver-rabbitmq
description: Como subir, operar e derrubar o ambiente local em Docker (SQL Server do relay de Pagamentos, RabbitMQ com filas quorum e Toxiproxy) com Docker Compose, sem versionar credenciais (.env, .env.example). Use ao executar a aplicação Pagamentos ou Extrato, ao rodar testes de integração, ao configurar docker compose, SQL Server, RabbitMQ, Toxiproxy, portas, healthcheck, volumes ou variáveis de ambiente de conexão.
metadata:
  author: clovis-cli
  type: technical-skill
---

# Ambiente Docker com SQL Server e RabbitMQ

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar. O critério é a natureza da mudança: uma mudança de comportamento ou de decisão (nova imagem ou versão adotada, novo serviço, nova variável, novo procedimento) pede atualização; um refactor técnico que preserva o procedimento (renomear serviço interno, reordenar o arquivo) não pede. Se esta skill disser X e o repositório fizer Y sem decisão registrada que resolva, não "corrija" nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

O relay de Pagamentos usa SQL Server executado em Docker porque as duas estratégias de relay dependem de recursos próprios dele (`UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion` com `min_active_rowversion()`), ausentes no H2 (decisão D5). Extrato e Reconciliação usam H2 em arquivo e não precisam de contêiner de banco. O broker é RabbitMQ com filas quorum, publisher confirms e ack manual. O Toxiproxy fica entre a aplicação e o broker (e, quando preciso, o banco) para injetar falhas de rede.

Existem dois modos de uso, independentes entre si:

| Modo | Quem sobe os contêineres | Para que serve |
|---|---|---|
| Docker Compose (`compose.yaml`) | a pessoa, com `docker compose` | executar Pagamentos e Extrato manualmente e inspecionar filas e tabelas |
| Testcontainers | o próprio teste de integração | testes automatizados, com portas aleatórias e ciclo de vida do teste |

Os testes automatizados usam Testcontainers e sobem seus próprios contêineres; não dependem do Compose estar de pé nem devem se conectar às portas dele.

## Como aplicar

### Artefatos e localização

- `compose.yaml` na raiz do repositório, com os serviços `sqlserver`, `rabbitmq` e `toxiproxy`.
- `.env.example` na raiz, versionado, com os nomes das variáveis e valores de exemplo claramente fictícios.
- `.env` na raiz, criado localmente a partir do `.env.example`, **nunca versionado**.
- Antes de criar o `.env`, confirme que o `.gitignore` o lista (`.env`). Se faltar, adicione a entrada primeiro.

### Variáveis de ambiente

| Variável | Uso |
|---|---|
| `MSSQL_SA_PASSWORD` | senha do usuário `sa` do SQL Server; precisa ter ao menos 8 caracteres e combinar três das quatro classes (maiúscula, minúscula, dígito, símbolo), ou o contêiner encerra na partida |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` | credenciais iniciais do RabbitMQ |

O `compose.yaml` lê esses valores do `.env` por substituição (`${MSSQL_SA_PASSWORD}`) e falha na partida se faltarem (`${MSSQL_SA_PASSWORD:?defina no .env}`). As aplicações recebem usuário, senha e URLs por variáveis de ambiente (nos apps Spring Boot, as propriedades padrão `SPRING_DATASOURCE_*` e `SPRING_RABBITMQ_*`), nunca por valores fixos no código ou em `application.yml` versionado. O SQL Server exige `ACCEPT_EULA=Y` no serviço.

### Serviços

- **sqlserver:** imagem `mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04` (a versão em que a semântica de `min_active_rowversion()` é confirmada e registrada); porta 1433, publicada pela variável `MSSQL_PORT`; volume nomeado para os dados; `healthcheck` que executa `/opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$$MSSQL_SA_PASSWORD" -Q "SELECT 1"` (o caminho `/opt/mssql-tools/bin/sqlcmd` é o do SQL Server 2019, e o `sqlcmd` dessa versão não usa `-C`); a imagem exige cerca de 2 GB de memória disponível para o Docker. Crie o banco de Pagamentos por script de inicialização ou pela migração da aplicação.
- **rabbitmq:** imagem `rabbitmq:4.3.6-management`; portas 5672 (AMQP) e 15672 (console), publicadas pelas variáveis `RABBITMQ_AMQP_PORT` e `RABBITMQ_MGMT_PORT`; `healthcheck` com `rabbitmq-diagnostics -q ping`. As filas são declaradas pela aplicação com `x-queue-type: quorum` e durable; a DLQ também é declarada pela aplicação. Filas não durable, exclusivas ou auto-delete não são aceitas como quorum.
- **toxiproxy:** imagem `ghcr.io/shopify/toxiproxy:2.10.0`; porta 8474 (API de controle), publicada pela variável `TOXIPROXY_API_PORT`, e as portas dos proxies (por exemplo, uma porta que encaminha para `rabbitmq:5672`). Com o Toxiproxy no caminho, a aplicação aponta para a porta do proxy; conectar direto na porta do broker contorna as falhas injetadas.

Use `depends_on` com `condition: service_healthy` para que o Toxiproxy e as ferramentas de apoio só subam depois dos serviços de que dependem.

### Operação

```
docker compose up -d --wait          # sobe e espera os healthchecks
docker compose ps                    # estado dos serviços
docker compose logs -f rabbitmq      # logs de um serviço
docker compose stop rabbitmq         # simula indisponibilidade do broker
docker compose start rabbitmq        # restaura o broker
docker compose down                  # derruba, preservando volumes
docker compose down -v               # derruba e APAGA os dados dos volumes
```

Inspeção:

```
docker compose exec rabbitmq rabbitmqctl list_queues name type messages
docker compose exec sqlserver /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -Q "SELECT COUNT(*) FROM outbox"
```

(o caminho `/opt/mssql-tools/bin/sqlcmd` vale para a imagem 2019 fixada; outras versões do SQL Server usam `/opt/mssql-tools18/bin/sqlcmd` com `-C`). Confirme o conteúdo do volume antes de `down -v`: ele apaga o banco de Pagamentos.

### Testes de integração (Testcontainers)

- Dependências em escopo de teste: JUnit 5, `org.testcontainers:mssqlserver`, `org.testcontainers:rabbitmq` e `org.testcontainers:toxiproxy`. A compatibilidade das versões escolhidas é verificada pelo módulo `mensageria-outbox`, cuja suíte executa nos JDKs 8, 11, 17, 21 e 25 (decisão D6).
- Use as mesmas versões de imagem do `compose.yaml` para que o comportamento local e o dos testes coincidam.
- Contêineres de um mesmo conjunto de testes compartilham uma `Network` para que o Toxiproxy alcance o RabbitMQ pelo alias de rede.
- Credenciais dos contêineres de teste são descartáveis, geradas ou definidas no código de teste para uso efêmero; não reutilize nos testes os valores do `.env` e não copie os de teste para o `compose.yaml`.
- Os testes que dependem de SQL Server e RabbitMQ exigem Docker em execução; sem ele, falham na partida do contêiner.

### Bancos H2

Cada aplicação (Extrato e Reconciliação) usa o próprio arquivo H2, em caminho diferente dos demais (`jdbc:h2:file:...`). O arquivo H2 não faz parte do Compose. Em testes, crie o arquivo em diretório temporário por teste (`@TempDir`) para que cada execução comece vazia.

## Ferramentas e artefatos

- Docker e Docker Compose v2 (`docker compose`).
- `compose.yaml`, `.env.example`, `.env` (local), `.gitignore` na raiz.
- Console do RabbitMQ em `http://localhost:15672`; API do Toxiproxy em `http://localhost:8474`.
- Testcontainers e Toxiproxy para os testes automatizados.

## Restrições e armadilhas

- Nenhuma credencial, senha ou connection string com segredo vai para o repositório: nem em `compose.yaml`, `application.yml`, scripts ou testes versionados que apontem para o ambiente compartilhado.
- O `.env` precisa estar no `.gitignore` antes do primeiro commit que o toque; o `.env.example` guarda apenas nomes e placeholders.
- Tags `latest` tornam o ambiente irreprodutível: fixe a versão das imagens.
- O SQL Server encerra a partida se a senha não cumprir a complexidade exigida ou se `ACCEPT_EULA` não estiver definido; confira `docker compose logs sqlserver`.
- O SQL Server leva dezenas de segundos para aceitar conexões: conecte somente depois do `healthcheck`, com `--wait`.
- Portas 1433, 5672, 15672 e 8474 podem estar ocupadas por instâncias locais; ajuste o mapeamento no `compose.yaml` por variável de ambiente, e não no código da aplicação.
- Filas quorum exigem declaração com os mesmos argumentos em toda aplicação; declarar a mesma fila com argumentos diferentes causa erro `PRECONDITION_FAILED`.
- Nenhum valor de memória, latência ou throughput do ambiente é meta de desempenho; os limites explícitos de heap e de memória direta valem para o relay e o consumidor, não para os contêineres.
