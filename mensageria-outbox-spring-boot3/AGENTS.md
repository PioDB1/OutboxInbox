# AGENTS.md

Instruções canônicas para agentes de código nesta pasta. `CLAUDE.md` e `.github/copilot-instructions.md` apenas apontam para cá.

## Visão geral

O `mensageria-outbox-spring-boot3` é o módulo Maven que gera o artefato `br.com.db1.poc:mensageria-outbox-spring-boot3:0.3.0-SNAPSHOT`, a auto-configuração da biblioteca `mensageria-outbox` (lado produtor do padrão outbox) para aplicações **Spring Boot 3.x**: gravação da outbox na transação da aplicação, envio assíncrono ao RabbitMQ após o commit e recuperação dos pendentes na inicialização.

Relação com as pastas irmãs do componente:

- **`../mensageria-outbox-spring-boot-shared`** — código-fonte comum da integração (`OutboxAutoConfiguration`, `OutboxRelayProperties`, `OutboxGravador`, `OutboxGravada`, `OutboxDespachante`, `OutboxRecuperacao` e os testes), acrescentado às fontes deste módulo pelo `build-helper-maven-plugin`. O comportamento da integração vive lá.
- **`../mensageria-outbox-spring-boot4`** — o artefato equivalente para Spring Boot 4.x, com o mesmo código comum; os dois módulos diferem só no pacote do `RabbitProperties`.
- **`../mensageria-outbox`** — a biblioteca (`mensageria-outbox-core`, dependência de compilação) e o POM pai deste módulo.

Este módulo guarda só o que depende da linha 3.x do Spring Boot: `OutboxRabbitConnectionFactoryConfiguration` e o arquivo `AutoConfiguration.imports`. O repositório não tem aplicação Spring Boot 3.x: o artefato é verificado só pelos testes unitários.

## Stack

- Java 17 como piso: `maven.compiler.release` 17 e o enforcer `enforceBytecodeVersion` com `maxJdkVersion` 17, que sobrescreve o piso 8 do POM pai. O build roda em JDK 21.
- Spring Boot 3.x, compilado e testado com 3.5.16 (BOM `spring-boot-dependencies` importado); Spring como `provided`: `spring-boot-autoconfigure`, `spring-boot-starter-amqp` e `spring-boot-starter-jdbc`.
- POM pai `br.com.db1.poc:mensageria-outbox:0.3.0-SNAPSHOT` (`../mensageria-outbox/pom.xml`), que fixa o `com.rabbitmq:amqp-client` 5.37.0 no `dependencyManagement`, sobre a versão do BOM do Spring Boot (5.25.0).
- Dependência de compilação `br.com.db1.poc:mensageria-outbox-core:0.3.0-SNAPSHOT`.
- `build-helper-maven-plugin` 3.6.1 (fontes compartilhadas) e `maven-compiler-plugin` com `parameters=true`.
- Testes com JUnit Jupiter, AssertJ, `spring-boot-test` e `spring-test`.

## Estrutura

```text
mensageria-outbox-spring-boot3/
├── pom.xml                       # coordenadas, BOM 3.5.16, dependências provided, enforcer e build-helper
├── README.md                     # uso pela aplicação, configuração, comportamento, restrições e build
├── src/main/java/br/com/db1/poc/outbox/spring/OutboxRabbitConnectionFactoryConfiguration.java
├── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── .agents/                      # contexto e skills para agentes
```

O módulo consta do agregador Maven da raiz (`../pom.xml`).

## Convenções

- O código deste módulo se limita ao que difere entre as linhas do Spring Boot; o restante fica em `../mensageria-outbox-spring-boot-shared/`.
- `OutboxRabbitConnectionFactoryConfiguration` tem o mesmo nome e pacote (`br.com.db1.poc.outbox.spring`) nos dois módulos, é package-private e `@Configuration(proxyBeanMethods = false)`, porque o `OutboxAutoConfiguration` compartilhado a importa por `@Import`. Aqui ela usa `org.springframework.boot.autoconfigure.amqp.RabbitProperties`.
- O arquivo `AutoConfiguration.imports` lista apenas `br.com.db1.poc.outbox.spring.OutboxAutoConfiguration`.
- As versões do Spring vêm do BOM importado; o Spring é sempre `provided`, para que valha a versão do Spring Boot da aplicação.
- Código Java indentado com tabulação; `pom.xml` indentado com quatro espaços; comentários e Javadoc em português.

## Restrições globais

- Uma alteração em `OutboxRabbitConnectionFactoryConfiguration` é replicada no módulo `../mensageria-outbox-spring-boot4`, preservando o pacote do `RabbitProperties` de cada linha.
- Uma mudança no comportamento comum é feita em `../mensageria-outbox-spring-boot-shared/`, nunca copiada para este módulo; ela precisa compilar e passar nos testes dos dois módulos.
- O código deste módulo compila com `--release 17` contra o Spring Boot 3.x; o código comum usa só APIs presentes no Spring Boot 3.x e no 4.x.
- Pendências conhecidas, com a decisão humana registrada, ficam em `.agents/context/pendencias.md`.
- Credenciais de banco e broker ficam em variáveis de ambiente ou `.env`, nunca versionadas.

## Comandos essenciais

Execute a partir da raiz do repositório (`..`), com Maven e JDK 21. Não há wrapper Maven nem ferramenta de lint ou formatação configurada.

| Objetivo | Comando |
|---|---|
| Compilar e testar os dois módulos Spring Boot (e a biblioteca de que dependem) | `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify` |
| Instalar os dois artefatos e a biblioteca no repositório Maven local | `mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install` |

Os testes `*Test` (surefire) não exigem Docker.

## Estrutura `.agents/`

- `.agents/context/` — pendências conhecidas do módulo, com o que é, onde está, o efeito e a decisão humana (`pendencias.md`).
- `.agents/skills/` — a skill de negócio deste módulo (o contrato do artefato para Spring Boot 3.x) e as skills de autoria usadas no desenvolvimento orientado a especificação (spec, plano, tarefas, casos de teste, execução de tarefas e autoria de skills), carregadas sob demanda pelo agente a partir da `description` do frontmatter de cada uma.

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
