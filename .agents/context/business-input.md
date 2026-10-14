---
name: business-input
description: Registro do material de negócio fornecido (texto digitado e spec-caso-a-outbox-inbox.md) e índice de proveniência por fonte, com seções e linhas do spec para reabertura, além dos arquivos de template herdados do repositório.
metadata:
  author: clovis-cli
  responsibility: Registro fiel do material de negócio fornecido pelo usuário e índice de proveniência e reacesso desse material, organizado por fonte. A Etapa 2 reabre as fontes originais a partir daqui; este arquivo não classifica domínios, fronteiras, dependências nem nível de confiança.
---

# Entrada de negócio

## Entrada do usuário

Texto digitado no campo de regras de negócio (preservado):

> leia o arquivo spec-caso-a-outbox-inbox.md para criar os domínios e specs para a aplicação

Restrição declarada no questionário da sessão (origem: texto fornecido pelo usuário):

> use base de dados H2 salvando em arquivo

Contexto da sessão (origem: texto fornecido pelo usuário): sistema do tipo POC para validação de conceito de uso de estrutura outbox-inbox; projeto greenfield; escopo no projeto inteiro.

Caminhos citados: `spec-caso-a-outbox-inbox.md` (raiz do repositório).

## Índice de fontes

### Fonte 1 — `spec-caso-a-outbox-inbox.md`

- **Tipo:** arquivo Markdown local, raiz do repositório (não versionado no momento da leitura).
- **Reacesso:** abrir o arquivo diretamente; leitura textual simples, sem credenciais.
- **Documento relacionado citado e não fornecido:** `spec-caso-b-saga-orquestrada.md` (Caso B, reaproveita o `mensageria-core`). Não existe no repositório.

| Tema | Localização (seção · linhas) |
|---|---|
| Contexto, objetivo e pergunta da PoC | §1 · linhas 8-14 |
| Escopo dentro e fora | §2 · linhas 16-26 |
| Restrições técnicas (banco, broker, Java, sem XA) | §3 · linhas 28-34 |
| Arquitetura e fluxo (diagrama Mermaid) | §4 · linhas 36-47 |
| Modelo de dados de Pagamentos (`pagamento`, `outbox`) | §5.1 · linhas 51-71 |
| Modelo de dados de Extrato (`extrato_lancamento`, `inbox`) | §5.2 · linhas 73-91 |
| Envelope padrão de mensagem | §6.1 · linhas 95-106 |
| Contrato `PagamentoRecebido` v1 | §6.2 · linhas 108-116 |
| Requisitos funcionais RF-A1 a RF-A11 | §7 · linhas 118-132 |
| Decisão em aberto do relay (claim × ponto de leitura) | §8 · linhas 134-146 |
| Requisitos não funcionais (observabilidade, memória, operabilidade, portabilidade) | §9 · linhas 148-153 |
| Fora de escopo de desempenho | §9.1 · linhas 155-157 |
| Invariantes A1 a A4 do reconciliador | §10 · linhas 159-168 |
| Cenários de aceite (fluxo feliz, A-F1 a A-F7, ordem por agregado) | §11 · linhas 170-217 |
| Tarefas sugeridas | §12 · linhas 219-228 |
| Critérios de conclusão | §13 · linhas 230-235 |
| Perguntas em aberto | §14 · linhas 237-242 |

### Fonte 2 — Arquivos de template herdados no repositório

- **Tipo:** arquivos versionados no commit `4298b6c` (Initial commit).
- **Reacesso:** abrir os arquivos; `git show 4298b6c --stat` lista o conteúdo inicial.

| Arquivo | Conteúdo relevante |
|---|---|
| `README.md` | Texto de template do projeto `db1group/java`: cita OpenJDK 11 e Gradle 6.3. Não descreve a PoC. |
| `.github/workflows/gradle.yml` | Workflow de CI de template com JDK 1.8 e `./gradlew build`. O Gradle foi removido no commit `d9134b5`. |
| `.gitignore` | Ignora `out`, `.gradle`, `.idea`, `build` e arquivos de IDE. |
| `LICENSE` | Licença do template. |

Não há código-fonte, scaffold, skills nem framework spec-driven concorrente (`.specify/`, `openspec/`) no repositório.

### Fonte 3 — Decisões humanas da rodada de lacunas

- **Tipo:** respostas do usuário no CLI Clovis (origem: texto fornecido pelo usuário). O registro completo das decisões está em `.agents/context/discovery-answers.md`.
