# Dependências técnicas do Mensageria Inbox

> **Maintaining this file**
>
> Atualize este documento sempre que uma dependência técnica do domínio for adicionada, removida ou mudar de efeito. Refactor que preserva as dependências não pede atualização. Divergência entre este arquivo e o projeto sem decisão registrada é escalada para a pessoa responsável.

Cada item diz o que é e o que afeta no domínio se faltar.

## Dependências técnicas do domínio

- **Ambiente Docker com RabbitMQ (e Toxiproxy para testes)** — sem ele o consumidor não é executável nem testável contra um broker com filas quorum e ack manual.
- **Testcontainers e Toxiproxy** — sem eles os cenários de falha do lado consumidor (consumidor cai entre commit e ack, mensagem veneno, repetição de mensagem) não são repetíveis.
- **Consumidor com ack manual** — sem ele a garantia de ack depois do commit não existe.
- **Convenção de logs estruturados com `messageId`, `correlationId` e `aggregateKey`** — sem ela a rastreabilidade ponta a ponta não existe.

## Dependências externas relevantes

- **H2 em arquivo** — banco da inbox onde os recursos exclusivos do SQL Server não são necessários; o módulo precisa de dialeto para ele. Sem o dialeto, a inbox não funciona sobre H2. Cada aplicação consumidora usa um arquivo H2 próprio, separado dos demais bancos.
- **RabbitMQ** — broker com filas quorum e ack manual, acessado por adaptador. Sem ele não há consumo.
- **Micrometer** — métricas de consumo (duplicatas descartadas, mensagens na DLQ, tentativas de retry). Sem ele as métricas não são expostas.
- **Java 21 e Maven multi-módulo** — plataforma e build; sem framework de aplicação no módulo, com adaptadores finos para Spring Boot 3.x. Sem a plataforma o módulo não compila nem executa.

## Dependências identificadas na investigação do domínio

- **Driver JDBC do H2** — sem ele o acesso à inbox não funciona.
- **Scripts de criação de esquema da inbox** — a tabela precisa existir no banco do consumidor antes da execução, com chave primária em `message_id`; sem ela o dedup falha.
- **Configuração dos limites de heap e de memória direta do processo do consumidor** — sem ela o requisito de memória estável sob carga não é verificável.
- **Credenciais do broker fornecidas por variáveis de ambiente ou arquivo local não versionado** — sem elas o consumidor não conecta; versioná-las é proibido.
- **Declaração da DLQ e da fila principal (quorum, durável) pela aplicação** — sem a DLQ declarada, a mensagem veneno não tem destino e bloqueia a fila.
