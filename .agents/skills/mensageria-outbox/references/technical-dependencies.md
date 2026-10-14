# Dependências técnicas do Mensageria Outbox

> **Maintaining this file**
>
> Atualize este documento sempre que uma dependência técnica do domínio for adicionada, removida ou mudar de efeito. Refactor que preserva as dependências não pede atualização. Divergência entre este arquivo e o projeto sem decisão registrada é escalada para a pessoa responsável.

Cada item diz o que é e o que afeta no domínio se faltar.

## Dependências técnicas do domínio

- **Ambiente Docker com SQL Server e RabbitMQ (e Toxiproxy para testes)** — sem ele o relay não é executável nem testável contra os recursos do SQL Server.
- **Testcontainers e Toxiproxy** — sem eles os cenários de falha do lado produtor (relay cai depois de publicar, broker indisponível, rede instável, dois relays ativos e commits fora da ordem de `seq`) não são repetíveis.
- **ADRs** — registram a escolha entre as estratégias de relay (critério de conclusão do domínio); sem eles a decisão e as observações da PoC não ficam registradas.
- **Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey`** — sem ela a rastreabilidade ponta a ponta não existe.

## Dependências externas relevantes

- **SQL Server** — banco da outbox, do lease e do relay, executado em Docker; as duas estratégias de relay dependem dos recursos `UPDLOCK`/`READPAST`/`ROWLOCK` e `rowversion`/`min_active_rowversion()`. O H2 não oferece esses recursos e não é usado para o relay. Sem ele nenhuma das estratégias de relay funciona.
- **RabbitMQ** — broker com filas quorum e publisher confirms, acessado por adaptador. Sem ele não há publicação.
- **Micrometer** — métricas de publicação (lag da outbox, pendentes, tentativas de retry). Sem ele as métricas não são expostas.
- **Java 21 e Maven multi-módulo** — plataforma e build; sem framework de aplicação no módulo, com adaptadores finos para Spring Boot 3.x. Sem a plataforma o módulo não compila nem executa.

## Dependências identificadas na investigação do domínio

- **Driver JDBC do SQL Server** — sem ele o acesso a outbox, lease e ponto de leitura não funciona.
- **Scripts de criação de esquema (outbox, lease e ponto de leitura)** — as tabelas precisam existir no banco de origem antes da execução; sem eles a gravação na outbox e o lease falham. A estrutura da outbox precisa incluir os metadados de propagação e, para a estratégia de ponto de leitura, a coluna `rowversion`.
- **Configuração dos limites de heap e de memória direta do processo do relay** — sem ela o requisito de memória estável sob carga não é verificável.
- **Credenciais de banco e broker fornecidas por variáveis de ambiente ou arquivo local não versionado** — sem elas o relay não conecta; versioná-las é proibido.
- **Contexto de rastreamento que forneça o `traceId` na origem da mensagem** — sem ele o `traceId` não é persistido na outbox nem propagado nos headers e nos logs ao longo do salto assíncrono.
