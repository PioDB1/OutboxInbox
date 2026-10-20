---
name: mensageria-outbox-spring-boot3
description: Contrato do artefato mensageria-outbox-spring-boot3, auto-configuração da outbox para Spring Boot 3.x. Use ao adotar o artefato numa aplicação Spring Boot 3.x ou ao alterar seu pom.xml, coordenadas e versão, piso Java 17, dependências provided, fixação do amqp-client 5.37.0 (rabbit-amqp-client.version), RabbitProperties de org.springframework.boot.autoconfigure.amqp, OutboxRabbitConnectionFactoryConfiguration e o bean outboxRabbitConnectionFactory com recuperação automática, ou o AutoConfiguration.imports.
metadata:
  author: clovis-cli
  type: domain-skill
---

# Artefato mensageria-outbox-spring-boot3

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar de comportamento ou de decisão (outras coordenadas ou linha do Spring Boot, outro piso de Java, outra dependência, outra forma de fixar o `amqp-client`, outro bean ou outro registro da auto-configuração). Refactor técnico que preserva a regra (renomear, extrair, reorganizar) não pede atualização. Se a skill disser X e o código ou o estado do projeto fizer Y sem decisão registrada que resolva, não ajuste nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

O `mensageria-outbox-spring-boot3` é o artefato que entrega a integração da biblioteca `mensageria-outbox` (lado produtor do padrão outbox) a aplicações **Spring Boot 3.x**: gravação da outbox na transação da aplicação, envio assíncrono ao RabbitMQ após o commit e recuperação dos pendentes na inicialização.

O comportamento da integração vem do código comum em `../mensageria-outbox-spring-boot-shared/` e é o mesmo do artefato irmão `mensageria-outbox-spring-boot4` (Spring Boot 4.x). Este módulo contém só o que depende da linha 3.x do Spring Boot.

## Contrato do artefato

| | |
|---|---|
| Coordenadas | `br.com.db1.poc:mensageria-outbox-spring-boot3:0.3.0-SNAPSHOT` |
| Linha do Spring Boot | 3.x; compilado e testado com 3.5.16 (BOM `spring-boot-dependencies` importado no `pom.xml`) |
| Java (piso) | 17: `maven.compiler.release` 17 e o enforcer `enforceBytecodeVersion` com `maxJdkVersion` 17 |
| POM pai | `br.com.db1.poc:mensageria-outbox:0.3.0-SNAPSHOT` (`../mensageria-outbox/pom.xml`) |
| Dependência de compilação | `br.com.db1.poc:mensageria-outbox-core:0.3.0-SNAPSHOT`, que traz `com.rabbitmq:amqp-client` 5.37.0 |
| Dependências `provided` | `spring-boot-autoconfigure`, `spring-boot-starter-amqp`, `spring-boot-starter-jdbc` |
| Dependências de teste | `spring-boot-test`, `spring-test`, `assertj-core`, `junit-jupiter` |

## Regras

- **R1. Spring como `provided`.** Em execução vale a versão do Spring Boot 3.x da aplicação, que declara os starters `amqp` e `jdbc` (ou `data-jpa`) e o driver do SQL Server. Aplicações Spring Boot 4.x usam o artefato `mensageria-outbox-spring-boot4`.
- **R2. Fixação do `amqp-client`.** A biblioteca é compilada e testada com o `amqp-client` 5.37.0; o BOM do Spring Boot 3.5.16 gerencia a 5.25.0. No build do módulo, o POM pai fixa a 5.37.0 no seu `dependencyManagement`, sobre a versão do BOM. A aplicação consumidora fixa a mesma versão:
  - com `spring-boot-starter-parent`, pela propriedade `<rabbit-amqp-client.version>5.37.0</rabbit-amqp-client.version>`;
  - com o BOM `spring-boot-dependencies` importado, declarando `com.rabbitmq:amqp-client` 5.37.0 no próprio `dependencyManagement`.
- **R3. Conteúdo do módulo.** Este módulo contém só `src/main/java/br/com/db1/poc/outbox/spring/OutboxRabbitConnectionFactoryConfiguration.java` e `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. `OutboxAutoConfiguration`, `OutboxRelayProperties`, `OutboxGravador`, `OutboxGravada`, `OutboxDespachante`, `OutboxRecuperacao` e os testes `OutboxAutoConfigurationTest` e `OutboxDespachanteTest` vêm de `../mensageria-outbox-spring-boot-shared/`, acrescentados às fontes pelo `build-helper-maven-plugin` (execuções `add-shared-source` e `add-shared-test-source`). Uma mudança no comportamento comum é feita na pasta compartilhada, nunca copiada para cá.
- **R4. `RabbitProperties` da linha 3.x.** `OutboxRabbitConnectionFactoryConfiguration` usa `org.springframework.boot.autoconfigure.amqp.RabbitProperties` (do `spring-boot-autoconfigure`) e o habilita com `@EnableConfigurationProperties(RabbitProperties.class)`. Essa é a única diferença de código em relação ao módulo `mensageria-outbox-spring-boot4`; uma alteração na classe é replicada no módulo irmão, preservando o pacote do `RabbitProperties` de cada linha.
- **R5. Mesmo nome e pacote.** A classe se chama `OutboxRabbitConnectionFactoryConfiguration`, fica no pacote `br.com.db1.poc.outbox.spring`, é package-private e `@Configuration(proxyBeanMethods = false)`: o `OutboxAutoConfiguration` compartilhado a importa por `@Import` com esse nome.
- **R6. Bean `outboxRabbitConnectionFactory`.** É um `com.rabbitmq.client.ConnectionFactory` preenchido pelos métodos `determine*` do `RabbitProperties` com host, porta, usuário, senha e virtual host (este só quando não nulo), com a **recuperação automática de conexão ligada** (`setAutomaticRecoveryEnabled(true)`), que o contrato da biblioteca exige. SSL, timeouts e as demais opções de `spring.rabbitmq.*` não são aplicados a essa fábrica. A fábrica interna do `CachingConnectionFactory` do Spring AMQP desliga essa recuperação e por isso não é reaproveitada. O bean é usado pelo `outboxPublisher` (pelo qualificador `outboxRabbitConnectionFactory`) e pela aplicação para declarar as suas filas.
- **R7. Condição do bean.** No código, `outboxRabbitConnectionFactory` é declarado sem `@ConditionalOnMissingBean`, embora o `README.md` (seção "Comportamento") declare todos os beans com essa condição: uma aplicação que define o próprio bean `outboxRabbitConnectionFactory` falha na inicialização. A divergência está registrada como pendência, sem correção decidida, em `.agents/context/pendencias.md`.
- **R8. Registro da auto-configuração.** O arquivo `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lista apenas `br.com.db1.poc.outbox.spring.OutboxAutoConfiguration`; a `OutboxRabbitConnectionFactoryConfiguration` entra pelo `@Import` dessa classe. O teste `registraAAutoConfiguracaoNoSpringBoot` verifica o arquivo.

## Verificação

O repositório não tem aplicação Spring Boot 3.x: este artefato é verificado só pelos testes `OutboxAutoConfigurationTest` e `OutboxDespachanteTest`, compilados e executados contra o Spring Boot 3.5.16, sem banco nem broker. `preencheAFabricaDoRabbitMqPelasChavesSpringRabbitmq` verifica o preenchimento do `outboxRabbitConnectionFactory` e a recuperação automática ligada. A validação de ponta a ponta contra SQL Server e RabbitMQ foi feita com o artefato Spring Boot 4.x, que compartilha o mesmo código.

Build, a partir da raiz do repositório, com Maven e JDK 21:

```
mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify    # compila e testa
mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am install   # instala no repositório Maven local
```

## Documentação completa

- `README.md` deste módulo: dependência, configuração (`spring.*` e `outbox.relay.*`), esquema, filas, gravação, comportamento e restrições.
- `../mensageria-outbox-spring-boot-shared/README.md`: o que é compartilhado entre os dois módulos e como entra no build.
- `../mensageria-outbox-spring-boot-shared/src/main/java/br/com/db1/poc/outbox/spring/`: código-fonte do comportamento comum, fonte da verdade do envio, da ordem por `aggregateKey` e da recuperação.
- `../mensageria-outbox/README.md`: contrato da biblioteca.
