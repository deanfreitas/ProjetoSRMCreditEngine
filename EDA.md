# EDA — mensageria, eventos e cache: o que eu adotaria, o que eu recusei e por quê

## Como ler este documento

Este é o registro escrito de uma discussão de arquitetura conduzida sobre **este** código, não um texto genérico sobre filas. A pergunta inicial foi operacional ("colocar SQS com 4 retentativas e DLQ na frente de create/update?") e evoluiu, opção por opção, até "trocar o HTTP pelo Kafka na escrita". Cada rodada é uma seção; cada seção termina com o veredito e o motivo.

O formato é o de um ADR longo — contexto, opções avaliadas, decisão, consequências e gatilhos de revisão — e cobre dois dos artefatos do nível staff do enunciado (§6): **proposta de arquitetura orientada a eventos para o fluxo de liquidação** e **ADR para a decisão difícil "síncrono vs. eventos"**. O `DECISIONS.md` §1.2 declarava esses itens como não perseguidos; esta é a parte que passou a existir, e a seção 10 aqui diz explicitamente o que continua fora.

**Nada neste documento está implementado.** Não há broker, produtor, consumidor, tópico, outbox ou cache de leitura no repositório. É proposta com premissa escrita, exatamente como o `SPEC.md` trata as ambiguidades do enunciado — e a decisão final, registrada na seção 8, é justamente **não adicionar mensageria agora**.

---

## 1. O ponto de partida importa (estado atual do código)

Toda a discussão só faz sentido contra o que já existe. Sem isso, qualquer resposta vira slide.

| Fato do código | Onde | Consequência para a discussão |
|---|---|---|
| Escritas são **síncronas** e devolvem o recurso: `201` + `Location` (`assignors`, `receivables`, `fx-rates`), `201`/`200` com `SettlementView` | `AssignorController`, `ReceivableController`, `FxRateController`, `SettlementController` | Qualquer fila na porta de entrada troca isso por `202 Accepted` sem o `id` de domínio |
| Idempotência é **contrato HTTP** só na liquidação: `Idempotency-Key` obrigatória, reserva em Redis + `uk_settlements_idempotency_key` / `uk_settlements_receivable` | `SettlementController`, `RedisIdempotencyStore`, `V1__initial_schema.sql` | A liquidação está pronta para consumo at-least-once; **o cadastro de recebível não tem chave de negócio única** |
| Retry, timeout e disjuntor já existem onde há rede alheia (`call-timeout: 800ms`, `max-attempts: 3`, backoff 100ms) | `ResilientFxRateProvider`, `FxConfiguration` | "Retry e timeout" já estão resolvidos na borda de leitura de terceiro; fila não substitui timeout, só muda quem espera |
| Validação vive no request/response: `400` (`@Valid`), `404` (cedente inexistente), `422` (vencimento anterior, face não positiva), `409`, `503` | `ApiExceptionHandler` | No consumidor de fila **não há para quem responder** |
| Pool pequeno e explícito: `maximum-pool-size: 10`, `connection-timeout: 3000` | `application.yml` | É o argumento mais forte a favor de fila (backpressure) — e também o que réplica de leitura resolve mais barato |
| Simulação é síncrona por natureza, sem efeito colateral, dependente da cotação **daquele instante** | `SimulationController` | Não é enfileirável em nenhum desenho. Enfileirar simulação é só latência |
| Redis aparece em **um** lugar: reserva de `Idempotency-Key`. Não há cache de cotação, sessão, rate limit ou lock genérico | `IdempotencyConfiguration` | "Usar Redis para consulta" é capacidade **nova**, não reuso de algo existente |
| O extrato faz **3 queries** por request: página com join de 3 tabelas, `count(*)` e `GROUP BY` com 4 `sum()` | `JpaSettlementStatementQuery.findBy` | É a leitura mais cara do sistema — e a que **não** se resolve com cache de chave |

**Premissa que atravessa tudo:** o PostgreSQL é a autoridade; Redis falha aberto (`management.health.redis.enabled: false`, `DisabledIdempotencyStore`, teste de modo degradado com `docker compose stop redis`). Nenhuma opção avaliada abaixo tem permissão para inverter isso.

---

## 2. Opção A — SQS Standard (4 retentativas + DLQ) na frente de create/update

### Vantagens

1. **Absorção de pico e proteção do Postgres.** Burst vira profundidade de fila em vez de `connection-timeout` estourando e `5xx` no cliente. O consumidor controla a vazão; o banco nunca vê mais carga do que aguenta.
2. **Retry correto para falha transitória.** Deadlock, `ConcurrentSettlementException` e `FxRateUnavailableException` (hoje `503`) deixam de ser erro do operador e passam a ser retentativa automática — exatamente a classe de falha em que repetir resolve.
3. **DLQ como caixa de evidência.** Mensagem que falhou 4 vezes fica preservada com payload íntegro, para inspeção, redrive e post-mortem. Hoje uma falha de escrita só existe no log.
4. **Desacoplamento temporal.** Banco em failover não derruba o `POST`: a API aceita e o consumidor processa quando a dependência volta.
5. **Isolamento de blast radius e throttling natural do terceiro.** Filas separadas por caso de uso; consumidor limitado mantém as chamadas ao provedor de câmbio abaixo de `max-concurrent-calls: 8` por construção, e não por rejeição.

### Desvantagens

1. **O contrato HTTP perde valor.** `201` + recurso vira `202 Accepted` sem `id` de domínio. O painel passa a precisar de polling, callback ou WebSocket; para liquidação isso é regressão de UX financeira — o operador quer saber **agora** se pagou.
2. **Validação sai do request/response.** Erro de validação é falha **permanente**: retentar 4 vezes é desperdício e a mensagem morre na DLQ carregando um erro que o cliente nunca vê. Mitigação (validar na borda e enfileirar só o que é válido) devolve parte do acoplamento que a fila veio remover.
3. **At-least-once + 4 retentativas exige idempotência em tudo.** `VisibilityTimeout` mal dimensionado reentrega mensagem em processamento. Sem chave de negócio única, `POST /receivables` via fila gera **título duplicado**. A liquidação sobrevive (tem `UNIQUE`); o cadastro **não** — é a maior objeção técnica no estado atual do código.
4. **Sem ordenação.** `create` e `update` do mesmo recurso podem chegar invertidos.
5. **Retry de fila multiplica o retry interno.** Uma tentativa já pode gastar 3 × 800ms no câmbio; × 4 recebimentos ≈ 10s de trabalho útil retendo o slot do consumidor — e o `in-progress-ttl: 30s` precisa ser coerente com isso, senão um retry legítimo bate em `SETTLEMENT_IN_PROGRESS`.
6. **Perda da transação única.** Hoje aceitar e gravar é um commit. Com fila, "aceitei" e "gravei" são dois estados: qualquer falha entre o HTTP e o `SendMessage` reintroduz escrita dual — daí o outbox.
7. **Complexidade e custo operacional**, e o risco de **DLQ que ninguém olha**: sem runbook e sem alarme, é perda de dados com aparência de resiliência.

### Veredito

**Não na porta de entrada.** SQS compra absorção de pico e retentativa automática, mas cobra em contrato HTTP, validação, ordenação e idempotência. Se entrar, `maxReceiveCount: 4` vale **só** para falha transitória (classificação explícita no consumidor), `VisibilityTimeout` de 30–60s cobrindo o pior caso do câmbio, e backoff via `ChangeMessageVisibility` — SQS não tem backoff nativo.

---

## 3. Opção B — SQS FIFO

### O que o FIFO resolve

1. **Ordem por recurso** via `MessageGroupId`: o `create` nunca é processado depois do `update` do mesmo título. Mata a objeção de desordem da opção A.
2. **Deduplicação nativa de 5 minutos** via `MessageDeduplicationId` — usando a própria `Idempotency-Key` que o `SettlementController` já exige, o duplo clique é barrado **antes** de gastar cotação, Redis e conexão do Hikari.
3. **Serialização natural elimina a concorrência que hoje é tratada com lock.** Uma mensagem por grupo em voo significa que duas liquidações concorrentes do mesmo recebível deixam de existir por construção: `ConcurrentSettlementException` e `SETTLEMENT_IN_PROGRESS` saem do caminho quente. É o ganho mais elegante do FIFO aqui.

### O que o FIFO não resolve (e é decisivo)

1. **A janela de dedup é de 5 minutos e não é configurável.** Retry do cliente 10 minutos depois, job de reprocessamento ou redrive da DLQ no dia seguinte passam direto. **FIFO não substitui `uk_settlements_idempotency_key`.** Quem trata FIFO como "idempotência resolvida" cria pagamento duplicado com 5 minutos e 1 segundo de atraso.
2. **A DLQ quebra a ordem e trava o grupo.** Enquanto uma mensagem é retentada, **todo o `MessageGroupId` está bloqueado**: 4 tentativas × `VisibilityTimeout` de 30–60s = 2 a 4 minutos de head-of-line blocking por mensagem ruim. E quando ela cai na DLQ, as seguintes do grupo são processadas — o `update` é aplicado sobre um `create` que morreu. A ordem de entrega foi preservada; a **consistência causal, não**.
3. **Vazão por grupo é 1.** Grupo grosso (`"receivables"`) torna a fila single-threaded e joga fora o ganho de absorção de pico; grupo granular (`receivableId`) dá paralelismo mas perde a ordem *entre* recursos relacionados.
4. **`assignor → receivable` é dependência entre grupos.** `ReceivableService.register` exige cedente existente (`AssignorNotFoundException` → `404`). Em grupos diferentes, o recebível pode ser processado antes do cedente existir e falhar 4 vezes com um erro **permanente** que na verdade era transitório. Forçar `MessageGroupId = assignorId` corrige — e serializa todos os recebíveis do mesmo cedente, o que é péssimo justamente para o "lote de títulos" do enunciado.
5. **Sem backoff nativo**, e cada retry mantém o grupo travado: backoff exponencial e ordenação são objetivos em conflito direto.
6. **Mais caro por requisição**, sufixo `.fifo`, sem delay por mensagem, 20.000 mensagens em voo (vs. 120.000 no Standard).

### Veredito

**Estritamente melhor que Standard para escrita de domínio**, e ainda assim não adotado na porta de entrada: troca duplicidade e desordem por head-of-line blocking e vazão serial, não devolve o `201` com o recurso nem a validação no request/response, e não dispensa a chave única no banco. Em FIFO, o alarme que importa é `ApproximateAgeOfOldestMessage` — aqui ele significa **grupo travado**, não "consumidor lento".

---

## 4. Opção C — Kafka em vez de SQS

Kafka não é "SQS melhor": é **log particionado e replayável**, com consumo por offset em vez de fila de trabalho com ACK por mensagem.

### Onde ganha das duas opções anteriores

1. **Ordem sem head-of-line blocking na retentativa.** Ordem é por partição; a mensagem ruim trava o offset, e o padrão é desviar para retry topic / DLT (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`) e seguir. Bloquear passa a ser decisão, não imposição do broker.
2. **Retry com backoff nativo no cliente** (`ExponentialBackOff`, `@RetryableTopic`).
3. **Classificação de falha de primeira classe:** `addNotRetryableExceptions(InvalidPricingInputException, ReceivableNotSettleableException, AssignorNotFoundException)` — o que no SQS exigia código próprio.
4. **Retenção e replay.** Reconstruir projeção, reauditar liquidação, repovoar histórico de cotação. É o que habilita tratar `fx_rates` (append-only) como stream de verdade.
5. **Paralelismo dentro da ordem:** serial por partição, paralelo entre N partições — continua protegendo o pool via `max.poll.records` e concorrência do listener.
6. **Fan-out nativo:** `SettlementSettled` alimentando contabilidade, notificação e extrato, cada um com seu consumer group. No SQS isso é SNS + N filas.
7. **Compaction como estado:** tópico compactado com chave = par de moedas dá "última cotação vigente" materializada.

### Onde fica igual ou pior

1. **O contrato HTTP continua degradado** — Kafka não muda nada disso, e a simulação segue não enfileirável.
2. **Perde a dedup de 5 minutos do FIFO.** `enable.idempotence=true` cobre retry de rede do **produtor** na sessão; não é idempotência de negócio. Consumo é at-least-once. Portanto a chave única no Postgres fica **mais** essencial, não menos.
3. **Transação Kafka não abrange o Postgres.** Escrever no banco e produzir no tópico continua sendo escrita dual → **outbox obrigatório** (com Debezium/CDC como opção).
4. **Rebalance é modo de falha novo.** `max.poll.interval.ms` estourado (liquidação gasta até 3 × 800ms + transação) causa rebalance, reprocessamento e duplicata — o análogo do `VisibilityTimeout`, com efeito em todo o grupo.
5. **Custo operacional muito maior:** broker/KRaft ou MSK, partições, retenção, `min.insync.replicas`, schema registry, monitoramento de lag. No `docker-compose.yml` atual (Postgres + Redis + app) entra broker + registry, e Testcontainers Kafka deixa a suíte de 96 testes sensivelmente mais lenta.
6. **DLT não é gerenciada:** retry topic e DLT são tópicos que você cria, monitora e faz redrive.
7. **Schema versionado passa a ser obrigatório** (Avro/Protobuf + registry): com retenção longa e replay, mensagem de hoje será lida por código de amanhã — o SQS perdoava isso porque a mensagem morria em 14 dias.

### Veredito

**Se houver fila neste projeto, Kafka é a escolha superior ao SQS** — resolve o pior defeito do FIFO (head-of-line blocking na retentativa) e o do Standard (desordem), e ainda dá replay e fan-out. Mas entra como **backbone de eventos**, não como porta de entrada de escrita.

---

## 5. Opção D — "Se colocar Kafka, posso tirar o Redis?"

**Sim, é possível tirar o Redis — mas não por causa do Kafka.** Ele já é removível hoje (`CREDIT_ENGINE_IDEMPOTENCY_ENABLED=false` → `DisabledIdempotencyStore`, e a decisão volta para dentro da transação). Kafka não assume **nenhuma** das funções que o Redis exerce aqui.

| Necessidade do guarda de idempotência | Kafka entrega? |
|---|---|
| Leitura **síncrona** de "essa chave já existe?" no meio do `POST` | **Não.** Log append/consumo por offset não tem `GET key`; KTable/state store existe no consumidor, não no controller |
| `SET NX` atômico (reserva exclusiva) | **Não.** Producer não faz compare-and-set |
| TTL de 30s / 24h **por chave** | **Não.** Retenção é por tópico; compaction não tem semântica de "expirou, libere o retry" |
| `release()` imediato no rollback | **Não.** Só via tombstone, assíncrono, sem garantia de visibilidade no próximo request |
| Teto de latência de 200ms no caminho do dinheiro | **Não**, dentro do request síncrono |
| Dedup de janela curta | Parcial e irrelevante: cobre retry de rede do produtor, não duplo clique do operador |

Kafka é **transporte e ordenação**; Redis é **estado de decisão com TTL e acesso aleatório**. São eixos ortogonais, e a pergunta se inverte: com consumo at-least-once, rebalance e redrive, a guarda de idempotência fica **mais** crítica. Tirar o Redis é viável (custa latência e trabalho desperdiçado em corrida); tirar a **chave única no Postgres** seria catastrófico — e nenhuma fila a substitui.

**O que realmente permitiria remover o Redis** (e não tem relação com Kafka): (a) aceitar o caminho degradado como normal — duas requisições concorrentes precificam e uma é descartada por `DataIntegrityViolationException`, correto e mais caro; ou (b) trocar a reserva antecipada por Postgres — tabela `idempotency_keys` com `INSERT ... ON CONFLICT DO NOTHING` em transação curta, ou `pg_advisory_xact_lock(hash(key))`. Essa é a alternativa já registrada em `DECISIONS.md` §5.4, e ela paga com mais uma conexão do pool de 10 por requisição — justamente a contenção que o Redis veio evitar.

**Com Kafka, o Redis tende a ganhar papéis**, não perder: dedup no consumidor (`SETNX processed:<eventId>` antes de tocar o banco), cache da cotação vigente projetada do tópico compactado, e acompanhamento de lote para o `202 Accepted` da ingestão.

---

## 6. Opção E — Kafka para escrita (via outbox) + Redis para leitura

Esta é a única opção da discussão que **não cobra o contrato HTTP** — e é a que eu adotaria se houvesse necessidade medida. "Kafka para escrita" tem duas leituras, e só uma funciona:

- **Como porta de entrada do `POST`** (command bus): recai em tudo da seção 2 — `202`, perda de `404`/`422`/`409`, simulação inviável, título duplicado sem chave única.
- **Como backbone de eventos da escrita, via outbox:** a escrita continua indo ao Postgres em uma transação (ACID e `UNIQUE` intactos) e o Kafka carrega o **fato consumado**.

```
POST /settlements  ->  commit no Postgres (autoridade) + outbox na MESMA transacao   [201 sincrono]
                   ->  publisher  ->  settlement.settled     (key = receivableId)
                                      receivable.registered  (key = assignorId)
                                      fx.rate.quoted         (key = USD/BRL, compactado)
                   ->  consumidor ->  Redis (cache/projecao de leitura)
                                  ->  statement_entries (read model do extrato)
```

### O lado da leitura, candidato por candidato

| Chave / recurso | TTL | Invalidação | Observação |
|---|---|---|---|
| `fx:current:{base}:{quote}` | 60s + jitter, ou sem TTL se projetado do tópico compactado | `fx.rate.quoted` | **Maior ganho.** `ResilientFxRateProvider.rateFor` consulta `storedRates` em *toda* simulação e *toda* liquidação; cardinalidade 1–2, hit ratio altíssimo |
| `settlement:{id}` e `settlement:by-receivable:{id}` | 24h | nunca | Dado **imutável** após o commit: `SET` é sempre seguro. É por onde começar |
| `receivable:{id}` | 300s | `DEL` no `settlement.settled` | Muda de `PENDING` para liquidado |
| `assignor:{id}` | 1h | `DEL` no `assignor.updated` | Quase estático |
| `statement:*` | **não cachear** | — | Chave seria `(from,to,assignorId,currency,page,size)`: cardinalidade quase infinita, hit ratio próximo de zero. Aqui é **projeção**, não cache |
| `AssignorRepository.findByDocument`, `findByIdempotencyKey` | **nunca** | — | Pré-check de escrita: a autoridade é o `UNIQUE` do banco |

**O extrato é o ponto que mais importa e o mais mal compreendido.** É a leitura mais cara do sistema (3 queries, join de 3 tabelas, `count` + `GROUP BY/sum`) e a de pior hit ratio por chave. O alívio real vem de **read model** desnormalizado (`statement_entries`, já com `document`, `legalName`, `receivableType`, `dueDate` embutidos) alimentado pelo consumidor, com totais pré-agregados incrementalmente — não de `GET`/`SET`.

### O que quebra, e como não quebrar

1. **Escrita dual no cache.** `SET` no Redis **dentro** da transação é bug: commit falhando (deadlock, `DataIntegrityViolationException` da `uk_settlements_receivable`) deixa o cache servindo liquidação fantasma. Regra: `@TransactionalEventListener(AFTER_COMMIT)` ou, melhor, **só o consumidor escreve no cache** — assim a ordem no Redis é a ordem do log, não a ordem das threads do Tomcat.
2. **Invalidação fora de ordem.** `miss lê PENDING → consumidor apaga → request grava PENDING` deixa o cache **permanentemente errado**. Defesa: só `DEL` pelo consumidor (idempotente e comutativo, diferente do `SET`), ou escrita condicional por versão em Lua — padrão que já existe no `RedisIdempotencyStore`.
3. **Cache como fonte de verdade.** O cache de leitura **herda** a propriedade que o guarda de idempotência já tem: Redis fora → miss → banco, nunca `5xx`. Com o teto de `timeout: 200ms`, porém, há efeito novo: em Redis degradado cada `GET` paga **200ms + query**, ficando mais lento que sem cache. Isso pede disjuntor no cache (resilience4j já está no projeto).
4. **Cache stampede em `fx:current:USD:BRL`.** Chave única lida por toda requisição: na expiração, N requisições vão ao banco e, sem taxa fresca, ao provedor externo — batendo em `max-concurrent-calls: 8` e virando `RejectedExecutionException` → `503`. Cache mal feito **piora exatamente** o caminho que o `ResilientFxRateProvider` protege. Defesas: jitter, `SET NX` como lock de repopulação, projeção do tópico compactado (sem expiração).
5. **Cotação cacheada nunca vira bypass da defasagem.** O valor carrega `effectiveAt` e `credit-engine.fx.max-staleness: 12h` é revalidado **no leitor**, a cada uso. O provedor foi escrito explicitamente para não entregar taxa velha como plano B; trocar 3ms de Postgres por prejuízo cambial silencioso seria o pior negócio possível.
6. **Cache de ausência.** `GET /receivables/{id}` inexistente hoje é query + `404`; cliente em loop fura o cache 100% das vezes. Cachear negativo com TTL de 5–10s é barato, mas precisa ser invalidado no `receivable.registered`.

### Parametrização

```
key                  = assignorId (receivable.registered) | receivableId (settlement.settled)
acks                 = all ; min.insync.replicas = 2 ; enable.idempotence = true
auto.offset.reset    = earliest
enable.auto.commit   = false        # AckMode.RECORD ou MANUAL_IMMEDIATE
max.poll.records     = 10
max.poll.interval.ms = 60000        # cobre 3 x 800ms de cambio + transacao
DefaultErrorHandler  = ExponentialBackOff(1s, 2.0, 4 tentativas) + DLT
notRetryable         = InvalidPricingInputException, ReceivableNotSettleableException,
                       AssignorNotFoundException
```

### Métricas e alarmes que passam a ser obrigatórios

- `credit_engine.cache{key,result=hit|miss|stale|unavailable}`, no mesmo padrão de `credit_engine.idempotency{result=unavailable}`. **Sem hit ratio observável, cache é custo sem prova de benefício.**
- **Consumer lag**, que aqui não é métrica de throughput: com invalidação por evento, lag **é a janela de dado errado servido ao operador**. Lag de 30s = extrato 30s atrasado e `GET /receivables/{id}` mostrando `PENDING` já liquidado.
- Contagem da DLT, com runbook de redrive.
- **Teste de modo degradado**, análogo ao `docker compose stop redis` que já existe: com Redis fora, toda leitura continua correta; com consumidor parado, a projeção atrasa mas o `GET` de recurso individual continua correto.

---

## 7. Opção F — trocar o HTTP pelo Kafka na escrita

O desenho mais radical: o `POST` deixa de ser porta de escrita, o cliente produz no tópico e a API fica só com `GET`.

**Ganha:** backpressure perfeito (a taxa de escrita é a do consumidor, não a do Tomcat), escrita disponível com o banco fora, retry/DLT declarativos, **auditoria de tudo que foi pedido** — não só do que foi aceito (hoje um `422` só existe no log) — e ordem causal por chave.

**Perde, e é caro:**

1. **Não existe mais resposta.** Sem `201`, sem `Location`, sem `SettlementView`. "Pagou ou não?" passa a ser pergunta assíncrona em caminho de dinheiro.
2. **`404`/`422`/`409` desaparecem do contrato.** Tudo que o `ApiExceptionHandler` traduz vira mensagem na DLT. É preciso reinventar um canal de erro (`events.command.rejected` + tela de rejeições) — HTTP síncrono reimplementado pior.
3. **`SETTLEMENT_IN_PROGRESS` perde sentido:** sem request, não há quem recusar cedo; a serialização passa a ser só a da partição.
4. **O cliente precisa gerar o id** (UUID v7) e ele vira a chave de idempotência — mudança de contrato de **domínio**, não só de transporte.
5. **Simulação é inviável**, então o HTTP não é eliminado: sobrevive obrigatoriamente para simulação e para toda leitura. "Trocar HTTP por Kafka" se aplicaria a 4 dos ~9 endpoints.
6. **Autenticação, autorização, rate limit e `@Valid`** teriam de ser reimplementados no consumidor ou na ACL do broker. E acesso de produtor a um tópico é privilégio muito maior que acesso a um endpoint (ver `DECISIONS.md` §1.4: hoje não há autenticação nenhuma).
7. **Navegador não produz em Kafka.** Seria necessário um gateway HTTP→Kafka — isto é, **HTTP de novo**, só sem resposta útil. É o argumento que mata a proposta na forma literal.
8. **Schema substitui o OpenAPI como contrato público**, e o Swagger UI é declaradamente a interface do operador nesta entrega.

**Veredito: não trocar — somar.** A versão defensável é HTTP síncrono para o operador e Kafka onde o produtor é máquina.

---

## 8. Decisão

**Mantida a arquitetura síncrona atual. Nenhuma mensageria é adicionada nesta entrega.** Quando houver necessidade medida, a adoção segue a tabela abaixo.

| Caminho | Transporte decidido | Motivo |
|---|---|---|
| `POST /api/v1/simulations` | **HTTP síncrono** | Sem efeito colateral, depende da cotação daquele instante, o operador precisa do número na tela. Não é enfileirável em nenhum desenho |
| `POST /api/v1/settlements` unitário | **HTTP síncrono** | `201` + `Idempotency-Key` + `409` precoce; caminho de dinheiro com resposta imediata |
| `POST /assignors`, `/receivables`, `/fx-rates` | **HTTP síncrono** | Transação curta, `404`/`422` úteis, resposta com o recurso |
| Efeitos pós-liquidação (contábil, notificação, extrato) | **Kafka via outbox**, `key = receivableId` | Ganha retry, DLT, fan-out e desacoplamento **sem** perder atomicidade nem o `201` |
| Ingestão de lote de títulos (ERP do cedente) | **Kafka**, `key = assignorId`, `202 Accepted` + `GET /batches/{id}` | Produtor é máquina, ninguém espera resposta. É o caso que o enunciado descreve |
| Leitura de cotação vigente e de liquidação | **Redis** (cache/projeção) | `fx:current` é lida em toda simulação e liquidação; `settlement:{id}` é imutável |
| Extrato analítico | **Read model** alimentado por evento | 3 queries com join, `count` e `GROUP BY`; cache de chave não acerta |
| Reconstrução de projeção, auditoria, replay | **Kafka** | SQS não faz |
| Fila de trabalho simples, sem replay, sem operação | **SQS** | Se o requisito for só "trabalho em background", Kafka é overkill |

### Pré-requisitos que vêm **antes** de qualquer fila

1. **`Idempotency-Key` + chave de negócio única no cadastro de recebível.** Hoje repetir o `POST` cria outro título; com at-least-once, isso deixa de ser risco e passa a ser certeza. Este é o item bloqueante.
2. **Outbox na mesma transação da escrita.** Publicar fora da transação é escrita dual — o mesmo defeito que o `REVIEW.md` cobra do Anexo A, em outra ponta.
3. **Classificação de falha no consumidor.** `InvalidPricingInputException`, `ReceivableNotSettleableException`, `AssignorNotFoundException` são **permanentes**: vão direto para DLQ/DLT sem consumir retentativa. Em FIFO, retentar validação não é só desperdício — é head-of-line blocking.
4. **Chave única no PostgreSQL permanece, sempre.** FIFO falha aberto, Kafka falha aberto, Redis falha aberto: **o banco decide.** É o padrão já estabelecido em `DECISIONS.md` §5.2, aplicado à mensageria.
5. **Timeout por tentativa continua no consumidor.** O `TimeLimiter` do `ResilientFxRateProvider` não é substituível por fila; fila só muda quem espera.

### Ordem de adoção (mais barato e mais seguro primeiro)

1. **Medir.** `hikaricp_connections_pending`, latência por endpoint, `pg_stat_statements`. O `maximum-pool-size: 10` é intencional e explícito ("pool pequeno e explícito evita esconder contenção") — sem contenção medida, cache e broker são fé.
2. **Índices** que sustentem `ORDER BY s.settledAt DESC, s.id` e os filtros por `assignorId`/`settlement_currency`. Custa uma migration Flyway, não uma infraestrutura.
3. **Réplica de leitura.** `@Transactional(readOnly = true)` já está em todos os caminhos de leitura, então rotear é praticamente configuração. Entrega o objetivo declarado ("não sobrecarregar o banco") **sem nenhum modo de falha novo**.
4. **Redis para `fx:current` e `settlement:{id}`.** Maior ganho, menor risco — e `settlement:{id}` é imutável, então é por onde começar.
5. **Kafka via outbox + read model do extrato.** Resolve o item mais caro e é o maior custo operacional.

Pular de (1) para (5) é o que faz um projeto cuja força declarada é "motor ao centavo, ACID, evidência de teste" parecer resume-driven.

---

## 9. Consequências aceitas

- **Fica sem backpressure de fila.** Um pico de escrita hoje vira `connection-timeout: 3000` e erro para o cliente, não profundidade de fila. Aceito porque o cliente **é avisado** — e porque o gatilho de revisão está escrito abaixo.
- **Falha transitória continua sendo erro do operador.** `FxRateUnavailableException` responde `503` ("nada foi gravado, pode repetir") em vez de ser retentada automaticamente. É consistente com a decisão de não usar taxa defasada: quem decide operar com incerteza cambial é a mesa, não o `catch`.
- **Não há fan-out de efeitos.** Notificação, extrato contábil e integração externa não existem — e, quando existirem, o outbox é obrigatório desde o primeiro deles.
- **O extrato continua pagando 3 queries por request.** O critério de aceite do `SPEC.md` §5 (~100 mil liquidações, p95 < 500ms) é atendido por índice; acima disso, o read model entra.
- **Toda leitura vai ao PostgreSQL**, inclusive a cotação vigente em cada simulação e cada liquidação. É o maior desperdício conhecido e o primeiro a ser atacado.

## 10. O que este documento **não** entrega

Para não repetir o pecado que o `REVIEW.md` cobra do Anexo A — documento afirmando o que o código não faz:

- **Nada aqui está implementado.** Não há broker, tópico, produtor, consumidor, outbox, cache de leitura ou read model no repositório, e nenhum teste cobre esta proposta.
- O **design de alta escala** (1 milhão de transações/minuto: sharding, consistência eventual e o que muda na semântica de idempotência nessa escala) continua fora, como declarado em `DECISIONS.md` §1.2.
- O **post-mortem do Anexo B** continua fora. O `REVIEW.md` já tem a linha do tempo, as três janelas de corrida e a prevenção sistêmica; falta o formato de incidente com contenção e comunicação.
- Números de throughput, latência e custo aqui são **ordens de grandeza de análise**, não medição: não houve teste de carga neste repositório.

## 11. Gatilhos de revisão desta decisão

Cada gatilho é observável, não opinião:

| Gatilho | Ação |
|---|---|
| `hikaricp_connections_pending` sustentado > 0 em pico, ou p95 de escrita degradando | Réplica de leitura primeiro; fila só se a contenção for de **escrita** |
| Requisito real de **lote** de títulos vindo de ERP | Kafka com `key = assignorId` + `202 Accepted` + `GET /batches/{id}` |
| Primeiro consumidor de efeito (contábil, notificação, extrato externo) | Outbox na transação da liquidação + Kafka, `key = receivableId` |
| Extrato ultrapassando o p95 < 500ms do `SPEC.md` §5 após índice | Read model `statement_entries` alimentado por evento |
| `credit_engine.fx.lookups{outcome="stored"}` dominando o custo de request | Cache de `fx:current` com `effectiveAt` revalidado no leitor |
| Redis se mostrando caro de operar | Reserva de idempotência no próprio Postgres (`DECISIONS.md` §5.4) — e **não** troca por Kafka, que não faz `SET NX` |

---

## 12. Resumo em uma linha por opção

| Opção | Veredito |
|---|---|
| **SQS Standard** na frente de create/update | Compra absorção de pico e retentativa; cobra contrato HTTP, validação, ordenação e idempotência. **Não** |
| **SQS FIFO** | Compra ordem e dedup de 5 min; cobra vazão serial por grupo e head-of-line blocking na retentativa; não dispensa o `UNIQUE`. **Não na porta de entrada** |
| **Kafka** em vez de SQS | Compra replay, retenção, fan-out e retry/DLT sem travar a ordem; cobra cluster, rebalance e a perda da dedup do FIFO. **Sim, mas como backbone de eventos** |
| **Kafka permite tirar o Redis?** | Não: Kafka não faz `SET NX`, TTL por chave nem leitura síncrona. O Redis já era removível (é otimização); com Kafka tende a virar *mais* útil. **Pergunta mal posta** |
| **Kafka (outbox) para escrita + Redis para leitura** | A única opção que não cobra o contrato HTTP: invalidação causal em vez de TTL adivinhado, read model para o extrato, cache na cotação lida em toda simulação. **Sim, quando houver necessidade medida** |
| **Trocar HTTP por Kafka na escrita** | Apaga `201`, `404`/`422`/`409` e `SETTLEMENT_IN_PROGRESS`, obriga o cliente a gerar o id, exige reimplementar autenticação e validação — e, como navegador não produz em Kafka, acaba com um gateway HTTP na frente. **Não** |

**A frase que atravessa a discussão inteira:** fila resolve *transporte*, não *correção*. A correção mora na transação do PostgreSQL e nas chaves únicas — e é por isso que nenhuma das seis opções tem permissão para removê-las.
