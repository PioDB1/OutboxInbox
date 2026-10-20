# Tasks: Inclusão dupla de movimentos

- [ ] **T1. Atualização da documentação autoritativa (Skill Pagamentos)**
    - Depends on: none
    - Atualizar `.agents/skills/pagamentos/SKILL.md` para incluir a especificação do endpoint `POST /movimentos/inclusao_dupla`, o comportamento de inclusão atômica de múltiplos registros e o mecanismo de falha forçada por posição.

- [ ] **T2. Implementação da lógica de inclusão dupla no MovimentoService**
    - Depends on: T1
    - Criar o método `incluirDuplo` no `MovimentoService`.
    - Garantir o uso de `@Transactional`.
    - Implementar a gravação de duas entidades `MovimentoEntity` e dois envelopes na outbox.
    - Implementar o lançamento da `IllegalStateException` baseado no parâmetro `posicaoFalha`.

- [ ] **T3. Exposição do endpoint no MovimentoController**
    - Depends on: T2
    - Criar o método `incluirDuplo` no `MovimentoController` mapeado para `POST /movimentos/inclusao_dupla`.
    - Validar que a lista recebida tem exatamente 2 elementos.
    - Delegar a chamada para o `MovimentoService`.
    - Validar a compilação e o teste de scaffold `contextLoads`.
