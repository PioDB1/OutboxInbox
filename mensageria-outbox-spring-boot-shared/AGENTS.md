# AGENTS.md

Instruções canônicas para agentes de código nesta pasta. `CLAUDE.md` e `.github/copilot-instructions.md` apenas apontam para cá.

## Visão geral

O `mensageria-outbox-spring-boot-shared` é o código-fonte comum da auto-configuração que integra a biblioteca `mensageria-outbox` (lados produtor e consumidor do padrão outbox/inbox) a aplicações Spring Boot. Lado produtor: gravação da outbox na transação da aplicação, envio assíncrono ao RabbitMQ após o commit, com ordem por `aggregateKey`, e recuperação dos pendentes na inicialização. Lado consumidor: um `InboxConsumer` criado quando a aplicação define `inbox.consumer.queue` e um bean `InboxMessageHandler`, iniciado e parado pelo ciclo de vida do Spring, que executa cada mensagem em uma transação Spring junto do registro na inbox.

A pasta não é um módulo Maven: não tem `pom.xml`, não gera artefato e não consta do agregador da raiz (`../pom.xml`). Os dois módulos que a consomem acrescentam `src/main/java` e `src/test/java` daqui às próprias fontes com o `build-helper-maven-plugin` e compilam esse código contra a sua linha do Spring Boot.

Relação com as pastas irmãs do componente:

- **`../mensageria-outbox-spring-boot3`** — artefato `br.com.db1.poc:mensageria-outbox-spring-boot3` para Spring Boot 3.x; inclui este código.
- **`../mensageria-outbox-spring-boot4`** — artefato `br.com.db1.poc:mensageria-outbox-spring-boot4` para Spring Boot 4.x; inclui este código.
- **`../mensageria-outbox`** — a biblioteca (envelope, `OutboxWriter`, dialeto SQL Server, `RabbitMessagePublisher`, relay por polling, `InboxConsumer`, dialetos H2 e SQL Server da inbox, `RabbitMessageSource`) e o POM pai dos dois módulos.
- **`../demooutboxinbox`** — aplicação de demonstração (Spring Boot 4.1.1, JPA) que consome o artefato Spring Boot 4.x.

Cada módulo guarda apenas o que difere entre as linhas do Spring Boot: `OutboxRabbitConnectionFactoryConfiguration` e o arquivo `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Stack

- Java 17 como piso (`--release 17` nos dois módulos, com o enforcer recusando bytecode acima de 17); o build roda em JDK 21.
- Spring Boot 3.x (compilado e testado com 3.5.16 no módulo `mensageria-outbox-spring-boot3`) e 4.x (4.1.1 no módulo `mensageria-outbox-spring-boot4`), com o Spring como dependência `provided`: `spring-boot-autoconfigure`, `spring-boot-starter-amqp` e `spring-boot-starter-jdbc`.
- Biblioteca `br.com.db1.poc:mensageria-outbox-core:0.4.0-SNAPSHOT`, que traz o `com.rabbitmq:amqp-client` 5.37.0.
- SQL Server como único banco da outbox (`SqlServerOutboxDialect`); SQL Server ou H2 na inbox (`SqlServerInboxDialect` e `H2InboxDialect`, escolhidos pelo produto do `DataSource`). RabbitMQ com publisher confirms na publicação e ack manual no consumo.
- SLF4J com MDC para os logs de publicação e de consumo.
- Testes com JUnit Jupiter, AssertJ, `spring-boot-test` (`ApplicationContextRunner`), `spring-test` e H2 em memória (versão do BOM do Spring Boot, escopo `test`).

## Estrutura

```text
mensageria-outbox-spring-boot-shared/
├── README.md                                      # o que é compartilhado e como entra nos módulos
├── src/main/java/br/com/db1/poc/outbox/spring/    # OutboxAutoConfiguration, OutboxRelayProperties, OutboxGravador,
│                                                  # OutboxGravada, OutboxDespachante, OutboxRecuperacao,
│                                                  # InboxAutoConfiguration, InboxProperties, InboxDialects,
│                                                  # SpringInboxTransactionExecutor, InboxConsumerLifecycle
├── src/test/java/br/com/db1/poc/outbox/spring/    # OutboxAutoConfigurationTest, OutboxDespachanteTest,
│                                                  # InboxAutoConfigurationTest, SpringInboxTransactionExecutorTest,
│                                                  # FakeMessageSource
└── .agents/                                       # skills para agentes
```

Dependências, plugins e recursos (`AutoConfiguration.imports`) ficam nos `pom.xml` e em `src/main/resources` de cada módulo.

## Convenções

- Todas as classes ficam no pacote `br.com.db1.poc.outbox.spring`, o mesmo da `OutboxRabbitConnectionFactoryConfiguration` de cada módulo, que é package-private e entra em `OutboxAutoConfiguration` e em `InboxAutoConfiguration` por `@Import`.
- Os nomes da integração são em português (`OutboxGravador`, `OutboxDespachante`, `OutboxRecuperacao`); a API da biblioteca é em inglês.
- Todo bean de `OutboxAutoConfiguration` e de `InboxAutoConfiguration` é declarado com `@ConditionalOnMissingBean`, para que a aplicação possa substituí-lo.
- `InboxAutoConfiguration` só registra beans com `inbox.consumer.queue` definida e um bean `InboxMessageHandler` (condições no nível da classe); a aplicação só com a outbox sobe sem nenhum bean do consumidor.
- As chaves de configuração usam os prefixos `outbox.relay.*` e `inbox.consumer.*` da biblioteca, com os mesmos padrões.
- Logs de publicação e de consumo em português, com as chaves de MDC `messageId`, `correlationId`, `aggregateKey` e `traceId`.
- Testes com sufixo `*Test` (surefire), sem broker nem banco externo: auto-configuração com `ApplicationContextRunner`; outbox, publicador e `MessageSource` simulados em memória e a inbox sobre H2 em memória.
- Código Java indentado com tabulação; comentários e Javadoc em português.

## Restrições globais

- Uma alteração aqui muda os dois artefatos: ela precisa compilar e passar nos testes dos dois módulos, com APIs disponíveis no Spring Boot 3.x **e** no 4.x e no piso Java 17. O que difere entre as linhas vai para uma classe de mesmo nome e pacote em cada módulo, nunca para esta pasta.
- Uma dependência nova usada por este código é declarada no `pom.xml` dos dois módulos.
- A auto-configuração não instancia o relay por polling da biblioteca e não executa retenção, métricas Micrometer nem lease: a aplicação nunca usa os dois caminhos juntos, porque competiriam pelas mesmas linhas pendentes.
- As tabelas da outbox e a tabela `inbox` são criadas por script DDL da aplicação; a auto-configuração não chama o `OutboxSchemaInstaller` nem o `InboxSchemaInstaller`. A fila principal e a DLQ do consumidor também são declaradas pela aplicação.
- Credenciais de banco e broker ficam em variáveis de ambiente ou `.env`, nunca versionadas.

## Comandos essenciais

Execute a partir da raiz do repositório (`..`), com Maven e JDK 21. Não há wrapper Maven nem ferramenta de lint ou formatação configurada.

| Objetivo | Comando |
|---|---|
| Compilar e testar este código nos dois módulos (e a biblioteca de que dependem) | `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify` |
| Instalar os dois artefatos e a biblioteca no repositório Maven local | `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install` |

Os testes `*Test` dos módulos não exigem Docker.

## Estrutura `.agents/`

- `.agents/skills/` — a skill de negócio desta pasta (o contrato da integração Spring Boot da outbox) e as skills de autoria usadas no desenvolvimento orientado a especificação (spec, plano, tarefas, casos de teste, execução de tarefas e autoria de skills), carregadas sob demanda pelo agente a partir da `description` do frontmatter de cada uma.

## Precedência das skills

- Antes de criar ou editar arquivos, ou de implementar qualquer funcionalidade, verifique se alguma skill em `.agents/skills/` governa o caso — pela tecnologia, padrão de código ou arquitetura envolvidos, ou pelo domínio de negócio, funcionalidade ou módulo em questão — e siga-a antes de agir.
- As skills em `.agents/skills/` são autoritativas e prevalecem sobre padrões inferidos do código existente.
- O corpo de cada skill indica onde vive a documentação completa do seu assunto e qual fonte detém a verdade, dentro ou fora do repositório; siga essa indicação a partir da skill.
- Mantenha as skills atualizadas quando o comportamento que elas descrevem mudar.

## Escrita de comentários e documentação

Todo texto que vive neste repositório — comentário de código, docstring, skill, `AGENTS.md`, README, spec — descreve o estado atual, escrito para quem abre o arquivo hoje sem conhecer o histórico do arquivo nem a conversa que o produziu.

- **Descreva o que é, nunca a transição.** Ao editar um texto existente, reescreva o trecho a partir do resultado final, como se sempre tivesse sido assim. Uma frase que só faz sentido para quem viu a versão anterior — ou o seu próprio diff — não pertence ao arquivo: o que mudou pertence à mensagem de commit e à descrição do pull request.
- **Negue apenas para evitar um erro plausível.** Uma negação merece seu lugar quando um leitor competente de fato tentaria a alternativa e a frase diz por que ela falha, protegendo uma mudança futura. Contraste com a versão anterior, com uma alternativa que ninguém tentaria ou com o que o código já mostra é ruído que custa a atenção do leitor.
- **Faça a poda antes de concluir a tarefa.** Releia os textos que você criou ou alterou e corte o que falhar nas duas regras acima. Expressões como "não mais", "costumava", "agora é", "em vez de", "ao invés de", "diferentemente de" e "sem precisar" são os sintomas usuais — mantenha apenas as que sobrevivem à segunda regra.

Uma exceção: quando o assunto do texto **é** uma mudança — uma mensagem de commit, uma descrição de pull request, a spec de uma unidade de manutenção, um changelog — a transição é o conteúdo. A regra proíbe narrar a *edição do texto*, nunca a mudança de que o texto trata.
