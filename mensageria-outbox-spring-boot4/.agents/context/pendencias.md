---
name: pendencias
description: Pendências conhecidas do artefato mensageria-outbox-spring-boot4 com decisão humana registrada e sem correção decidida, cada uma com o que é, onde está, o efeito e a decisão com data. Inclui o bean outboxRabbitConnectionFactory declarado sem @ConditionalOnMissingBean, em divergência com o README.
metadata:
  author: clovis-cli
---

# Pendências

## P1. `outboxRabbitConnectionFactory` sem `@ConditionalOnMissingBean`

- **O quê:** o bean `outboxRabbitConnectionFactory` é declarado sem `@ConditionalOnMissingBean`, enquanto o `README.md`, na seção "Comportamento", afirma "Beans registrados, todos com `@ConditionalOnMissingBean`" e inclui esse bean na lista.
- **Onde:** `src/main/java/br/com/db1/poc/outbox/spring/OutboxRabbitConnectionFactoryConfiguration.java`, método `outboxRabbitConnectionFactory(RabbitProperties)`. A mesma situação existe no módulo irmão, em `../mensageria-outbox-spring-boot3/src/main/java/br/com/db1/poc/outbox/spring/OutboxRabbitConnectionFactoryConfiguration.java`.
- **Efeito:** uma aplicação que define o próprio bean `outboxRabbitConnectionFactory` falha na inicialização, porque a sobrescrita de definição de bean é desabilitada no Spring Boot. A aplicação não consegue substituir a fábrica do módulo por um bean com esse nome.
- **Decisão:** decisão humana de 2026-10-20 — registrar como pendência, sem correção do código.
