# Dependências técnicas do Mensageria Inbox

> **Maintaining this file**
>
> Atualize este documento sempre que uma dependência técnica do domínio for adicionada, removida ou mudar de efeito. Refactor que preserva as dependências não pede atualização. Divergência entre este arquivo e o projeto sem decisão registrada é escalada para a pessoa responsável.

Cada item diz o que é e o que afeta no domínio se faltar.

## Dependências técnicas do domínio

- **Ambiente Docker com RabbitMQ e SQL Server (e Toxiproxy para testes)** — sem ele o consumidor não é executável nem testável contra um broker com filas quorum e ack manual, nem os testes de integração da inbox sobre SQL Server rodam.
- **Testcontainers e Toxiproxy** — sem eles os cenários de falha do lado consumidor (consumidor cai entre commit e ack, mensagem veneno, repetição de mensagem) não são repetíveis.
- **Consumidor com ack manual** — sem ele a garantia de ack depois do commit não existe.
- **Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey`** — sem ela a rastreabilidade ponta a ponta não existe.

## Dependências externas relevantes

- **H2 em arquivo** — banco da inbox onde os recursos exclusivos do SQL Server não são necessários; o consumidor tem o dialeto `H2InboxDialect` para ele. Sem o dialeto, a inbox não funciona sobre H2. Cada aplicação consumidora usa um arquivo H2 próprio, separado dos demais bancos. O H2 não é dependência de compilação nem de execução do `core`: a aplicação hospedeira traz o driver; o `core` o usa em escopo `test`, na versão 2.2.224 (a última linha com bytecode Java 8).
- **SQL Server** — banco da inbox da aplicação consumidora que já o usa (banco do `demooutboxinbox`); o consumidor tem o dialeto `SqlServerInboxDialect`. Sem ele, a inbox não funciona sobre SQL Server.
- **RabbitMQ** — broker com filas quorum e ack manual, acessado por adaptador. Sem ele não há consumo.
- **Micrometer** — métricas de consumo (duplicatas descartadas, mensagens na DLQ, tentativas de retry). Sem ele as métricas não são expostas.
- **Java 8 e Maven multi-módulo** — plataforma e build; o consumidor é parte do artefato `br.com.db1.poc:mensageria-outbox-core` (pacote `br.com.db1.poc.inbox`), que tem Java 8 (bytecode e API) como piso, compilado com `--release 8` e verificado nos JDKs 8, 11, 17, 21 e 25. Sem framework de aplicação no `core`; a integração com Spring Boot vive nos adaptadores `mensageria-outbox-spring-boot3` (Spring Boot 3.x) e `mensageria-outbox-spring-boot4` (Spring Boot 4.x), com Java 17 como piso. Sem a plataforma o consumidor não compila nem executa.

## Dependências identificadas na investigação do domínio

- **Driver JDBC do H2** — sem ele o acesso à inbox não funciona.
- **Scripts de criação de esquema da inbox** — a tabela precisa existir no banco do consumidor antes da execução, com chave primária em `message_id`; sem ela o dedup falha.
- **Configuração dos limites de heap e de memória direta do processo do consumidor** — sem ela o requisito de memória estável sob carga não é verificável.
- **Credenciais do broker fornecidas por variáveis de ambiente ou arquivo local não versionado** — sem elas o consumidor não conecta; versioná-las é proibido.
- **Declaração da DLQ e da fila principal (quorum, durável) pela aplicação** — sem a DLQ declarada, a mensagem veneno não tem destino e bloqueia a fila.
