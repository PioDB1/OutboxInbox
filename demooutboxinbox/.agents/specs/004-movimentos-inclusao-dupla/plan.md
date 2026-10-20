# Plan: Inclusão dupla de movimentos

## Stack e estrutura

Aplicação Spring Boot 4.1.1 com Spring Data JPA e SQL Server. A integração com a outbox usa a biblioteca `mensageria-outbox-spring-boot4`. O desenvolvimento segue o padrão de pacotes por domínio, com o código de movimentação em `br.com.db1.demooutboxinbox.movimento`.

## Decisões técnicas

- **Endpoint**: `POST /movimentos/inclusao_dupla` aceitando uma `List<MovimentoRequest>`. Validação de tamanho da lista (exatamente 2) no Controller.
- **Transacionalidade**: O novo método no `MovimentoService` será anotado com `@Transactional`, garantindo que ambas as inserções na tabela `movimento` e ambas as gravações na `outbox` pertençam à mesma transação local.
- **Mecanismo de Falha**: Parâmetro `posicaoFalha` (0, 1, 2).
    - `0`: Fluxo normal.
    - `1`: Lança `IllegalStateException` após a gravação da primeira movimentação e seu respectivo evento na outbox.
    - `2`: Lança `IllegalStateException` após a gravação da segunda movimentação e seu respectivo evento na outbox, mas antes do fim do método (e consequentemente antes do commit).
- **Ordem dos Eventos**: Garantida pela sequência de chamadas ao `outboxGravador.gravar(envelope)`. A biblioteca atribui um `seq` crescente na tabela `outbox`, que é respeitado pelo despachante.
- **Identificadores**: Os `aggregateKey` dos eventos serão os IDs das respectivas movimentações gerados pelo banco (via `saveAndFlush`).

## Modelo de dados

Sem alterações no modelo de dados existente. Utiliza a tabela `movimento` e as tabelas da outbox (`outbox`, `outbox_lease`, `outbox_read_point`).

## Contratos externos

- **REST API**: Novo endpoint documentado via anotações Spring/Swagger no `MovimentoController`.
- **Eventos**: Mantém o contrato `MovimentoRegistrado` v1 definido na skill de pagamentos.

## Interface

Não aplicável (apenas API). A nova funcionalidade será visível no Swagger UI (`/swagger-ui.html`).

## Estratégia de teste

- **Verificação Manual**: Utilizar o Swagger UI para enviar requisições com diferentes valores de `posicaoFalha` e observar:
    1. O status code da resposta.
    2. A presença ou ausência de registros na tabela `movimento`.
    3. A presença ou ausência de mensagens na fila do RabbitMQ.
- **Teste de Scaffold**: Rodar `./mvnw test` para garantir que o contexto continua subindo corretamente.

## Impacto na documentação autoritativa

- **Skill `pagamentos`**: Será atualizada para incluir a descrição do novo endpoint `POST /movimentos/inclusao_dupla` e o comportamento de inclusão múltipla com falha forçada. Esta é uma mudança deliberada para expandir as capacidades de demonstração da POC.
