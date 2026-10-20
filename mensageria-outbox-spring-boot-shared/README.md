# mensageria-outbox-spring-boot-shared

Código-fonte comum aos módulos [`mensageria-outbox-spring-boot3`](../mensageria-outbox-spring-boot3/README.md) e [`mensageria-outbox-spring-boot4`](../mensageria-outbox-spring-boot4/README.md). Este diretório não é um módulo Maven: não tem `pom.xml`, não gera artefato e não consta do agregador da raiz. Os dois módulos têm o mesmo POM pai, `br.com.db1.poc:mensageria-outbox:0.3.0-SNAPSHOT`, e a mesma versão, 0.3.0-SNAPSHOT. Cada um dos dois módulos acrescenta `src/main/java` e `src/test/java` daqui às próprias fontes com o `build-helper-maven-plugin` (metas `add-source` e `add-test-source`) e compila esse código contra a sua linha do Spring Boot.

Conteúdo, no pacote `br.com.db1.poc.outbox.spring`:

- `OutboxAutoConfiguration`, `OutboxRelayProperties` (`outbox.relay.*`), `OutboxGravador`, `OutboxGravada`, `OutboxDespachante` e `OutboxRecuperacao`;
- `OutboxAutoConfigurationTest` e `OutboxDespachanteTest`, que rodam nos dois módulos.

Cada módulo guarda apenas o que difere entre as linhas do Spring Boot: `OutboxRabbitConnectionFactoryConfiguration` (o `RabbitProperties` fica em `org.springframework.boot.autoconfigure.amqp` no Spring Boot 3.x e em `org.springframework.boot.amqp.autoconfigure` no 4.x) e o arquivo `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. `OutboxAutoConfiguration` importa essa classe, que existe com o mesmo nome e pacote nos dois módulos.

Uma alteração aqui muda os dois artefatos e precisa compilar e passar nos testes de ambos, com APIs disponíveis no Spring Boot 3.x e no 4.x e no piso Java 17. A partir da raiz:

```
mvn -pl mensageria-outbox-spring-boot3,mensageria-outbox-spring-boot4 -am verify
```

Uso, configuração e comportamento estão nos READMEs dos dois módulos.
