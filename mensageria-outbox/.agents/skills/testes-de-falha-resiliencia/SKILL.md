---
name: testes-de-falha-resiliencia
description: Como escrever e executar de forma repetível os testes de falha dos cenários A-F1 a A-F7 (relay cai, consumidor cai antes do ack, broker indisponível, rede instável, dois relays, mensagem veneno, commits fora da ordem de seq) com Testcontainers e Toxiproxy, usando o reconciliador como verificador das invariantes A1 a A4. Use ao criar, alterar, depurar ou executar testes de falha, de resiliência, de injeção de falhas ou de caos.
metadata:
  author: clovis-cli
  type: technical-skill
---

# Testes de falha de resiliência

> **Maintaining this skill**
>
> Atualize este documento sempre que **qualquer regra descrita aqui** mudar. O critério é a natureza da mudança: uma mudança de comportamento ou de decisão (novo cenário, nova forma de injeção, novo critério de aprovação) pede atualização; um refactor técnico que preserva o procedimento (renomear classe de apoio, extrair método) não pede. Se esta skill disser X e o código fizer Y sem decisão registrada que resolva, não "corrija" nenhum dos lados: escale a divergência para a pessoa responsável.

## Visão geral

A PoC responde se produtor e consumidor, cada um com seu banco, mantêm consistência eventual correta sob falhas, sem transação distribuída. A suíte de falhas prova isso: cada cenário A-F1 a A-F7 injeta uma falha, espera o sistema convergir sem intervenção manual e então entrega o veredito ao **reconciliador**, que verifica as invariantes A1 a A4 a partir dos dois bancos. O critério de conclusão da PoC é: invariantes verdadeiras ao final de todas as falhas injetadas (spec §13).

Ambiente (decisão D6): Testcontainers (SQL Server para o banco de Pagamentos e a outbox, RabbitMQ com filas quorum) e Toxiproxy para falhas de rede. A compatibilidade das versões de Testcontainers, Toxiproxy e Micrometer é verificada pela suíte do módulo `mensageria-outbox` nos JDKs 8, 11, 17, 21 e 25. O Extrato usa H2 em arquivo temporário.

## Como aplicar

### Onde vive a suíte

No módulo `reconciliacao`, em escopo de teste, com dependências de teste em `mensageria-outbox`, `mensageria-inbox`, `pagamentos` e `extrato`. Uma classe por cenário (`FalhaAF1Test` ... `FalhaAF7Test`) estendendo uma base comum, no pacote `falhas`.

### Base comum

A base comum garante repetibilidade:

1. **Contêineres singleton por JVM de teste:** SQL Server, RabbitMQ e Toxiproxy sobem uma vez (campos `static`) em uma `Network` compartilhada; o Toxiproxy encaminha para o RabbitMQ pelo alias de rede, e a aplicação ou o relay conecta no proxy.
2. **Isolamento por teste** (`@BeforeEach`): remover todos os toxics e reabilitar os proxies; esvaziar tabelas `pagamento`, `outbox` e a de lease; purgar ou recriar filas e DLQ com nomes únicos por teste; criar novo arquivo H2 de Extrato em `@TempDir`; zerar os contadores de métricas (registro Micrometer novo por teste).
3. **Parametrização pela estratégia de relay** (`@ParameterizedTest` sobre as duas implementações da interface do relay, decisão D8). O cenário A-F7 é obrigatório para ambas; os demais também rodam nas duas, pois os dados alimentam a comparação do ADR de relay.
4. **Sem `Thread.sleep` como sincronização:** espere por condição com timeout explícito (por exemplo Awaitility ou laço de polling com limite) e falhe com mensagem que mostre o estado observado.
5. **Falhas determinísticas:** o ponto exato da falha é provocado por um decorator de teste sobre a interface do relay, do publicador ou do consumidor (por exemplo "lança exceção depois do publish e antes de marcar como enviado"), e não por `sleep` e `kill` em momento aleatório. Falhas de rede usam toxics do Toxiproxy.
6. **Veredito pelo reconciliador:** ao fim de cada cenário, depois de esperar convergência, execute o reconciliador contra os dois bancos e afirme A1 a A4. Como A1 e A4 valem "eventualmente", a verificação repete até um timeout e reporta as violações da última rodada. Não reimplemente as invariantes em SQL dentro do teste: o reconciliador é o oráculo, escrito a partir das invariantes e não do código do fluxo.
7. **Asserções específicas do cenário** vêm além do veredito (contagens, métricas, ordem de tentativas), nunca em substituição a ele.

### Cenários

| Cenário | Como injetar | Asserções além de A1 a A4 |
|---|---|---|
| A-F1 relay cai depois de publicar e antes de registrar o envio | decorator que lança após o publish confirmado e antes de marcar; reiniciar o relay | mensagem publicada duas vezes; Extrato descarta a repetição pela inbox; um único lançamento; métrica de duplicata descartada incrementada |
| A-F2 consumidor cai entre o commit e o ack | decorator no ack que fecha o canal ou lança antes do `basicAck`, depois do commit | broker reentrega; inbox detecta o `messageId`; um único lançamento |
| A-F3 broker indisponível e depois restaurado | `proxy.disable()` no Toxiproxy (ou parar o contêiner) durante um período; reabilitar | linhas acumulam na outbox (lag e pendentes crescem); após a volta, todas publicadas sem perda |
| A-F4 rede instável entre relay e broker | toxics `latency`, `timeout`, `reset_peer`, `limit_data` ou `slow_close` no proxy | intervalos entre tentativas crescem conforme o backoff configurado; número de tentativas limitado (sem tempestade); métrica de retry |
| A-F5 dois relays ativos ao mesmo tempo | iniciar duas instâncias do relay sobre o mesmo banco, forçando a sobreposição do lease (por exemplo, lease com expiração curta e uma instância congelada pelo decorator) | nenhuma mensagem perdida; duplicidades absorvidas pela inbox |
| A-F6 mensagem inválida (veneno) | publicar no broker uma mensagem com payload inválido junto de mensagens válidas de outras chaves e de uma mensagem seguinte da mesma chave | após o limite de tentativas a mensagem está na DLQ, junto da mensagem seguinte da mesma chave; as mensagens das outras chaves são processadas; fila principal não fica bloqueada |
| A-F7 transações concorrentes confirmam fora da ordem de `seq` | duas conexões JDBC com transação aberta: T1 insere na outbox (obtém `seq` menor) e espera; T2 insere (obtém `seq` maior) e confirma; rodar ciclos do relay; só então T1 confirma | nenhuma mensagem pulada, em ambas as estratégias; ordem por `aggregateKey` preservada |
| Ordem por agregado | dois eventos do mesmo `aggregateKey` em sequência, com falha no meio | aplicados na ordem em que ocorreram (A3) |

O cenário de fluxo feliz e o de falha na gravação do pagamento (nenhum pagamento nem linha na outbox) compõem a mesma suíte como linha de base.

### Execução

A suíte exige Docker em execução e roda dentro de `mvn clean verify`. Para executar apenas os testes de falha:

```
mvn -pl reconciliacao -am test -Dtest='FalhaAF*' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl reconciliacao -am test -Dtest='FalhaAF3*' -Dsurefire.failIfNoSpecifiedTests=false
```

A segunda linha executa um cenário. O `-Dsurefire.failIfNoSpecifiedTests=false` é necessário porque `-am` inclui módulos sem testes que casem com o padrão.

### Observações para o relatório

Cada execução coleta, por cenário e por estratégia: lag da outbox, pendentes, duplicatas descartadas, mensagens em DLQ, tentativas de retry e tempo de recuperação até as invariantes ficarem verdadeiras. Esses dados alimentam o relatório da PoC e a seção "Observações da PoC" do ADR de relay. São observação, não meta de desempenho.

## Ferramentas e artefatos

- JUnit 5; Testcontainers (`mssqlserver`, `rabbitmq`, `toxiproxy`); cliente Java do Toxiproxy para criar proxies e toxics; Awaitility ou equivalente para espera por condição.
- Reconciliador do módulo `reconciliacao` como verificador.
- Micrometer (registro em memória nos testes) para ler as métricas do RF-A10.
- Fonte dos cenários: `spec-caso-a-outbox-inbox.md` §10, §11 e §13. Procedimento do ambiente fora dos testes: Docker Compose (`compose.yaml`).

## Restrições e armadilhas

- Contêineres sem `Network` compartilhada impedem o Toxiproxy de alcançar o RabbitMQ; a aplicação que conecta direto na porta do broker não sofre as falhas injetadas.
- Toxics e proxies desabilitados que sobram de um teste contaminam o seguinte: a limpeza no `@BeforeEach` é obrigatória.
- Nome de fila fixo entre testes mistura mensagens de cenários diferentes e produz falsos positivos ou negativos.
- Um teste que passa por `sleep` fixo é intermitente por construção; troque por espera com condição.
- Não rebaixe a asserção do reconciliador para "fazer o teste passar". Violação de A1 a A4 é defeito do fluxo ou do cenário e precisa ser investigada.
- A-F5 só é válido se houver sobreposição real dos dois relays; assegure-a com sincronização explícita (latch) e não com sorte de temporização.
- Nenhum tempo de execução do teste é meta de desempenho; os timeouts existem para evitar travamento, com folga suficiente para a máquina mais lenta da equipe.
- Credenciais usadas pelos testes são efêmeras dos contêineres de teste; nada de segredo real no repositório.
