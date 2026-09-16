# Arquitetura — SRM Credit Engine

Diagrama C4 nos níveis **1 (contexto)** e **2 (containers)**, mais o **modelo de dados (ER)**, um recorte de componentes e os dois fluxos que sustentam o caminho de dinheiro. Este documento **complementa** o [`README.md`](README.md) (camadas, stack, resiliência do câmbio, contrato de erros, observabilidade) e o [`SPEC.md`](SPEC.md) (premissas de negócio e precisão) — quando a decisão já está defendida lá, aqui fica só o que o desenho mostra e o porquê da forma.

Todo elemento dos diagramas corresponde a código deste repositório: pacote, classe, tabela, endpoint, métrica ou variável de ambiente. Caixa sem contraparte no código não entrou.

---

### Nível 1 — Contexto de sistema

```mermaid
flowchart TB
    operador["Operador de mesa<br/>pessoa"]
    engine["SRM Credit Engine<br/>precificacao e liquidacao de recebiveis<br/>API REST JSON"]
    fx["Provedor externo de cotacao<br/>porta ExternalFxRateSource<br/>hoje MockExternalFxRateSource"]
    db[("PostgreSQL<br/>recebiveis, liquidacoes,<br/>cotacoes, cedentes")]
    redis[("Redis<br/>reserva da Idempotency-Key<br/>porta IdempotencyStore")]

    operador -->|"simula, registra e liquida<br/>HTTP JSON com Idempotency-Key"| engine
    engine -->|"consulta cotacao do par<br/>quando nao ha taxa fresca em casa"| fx
    engine -->|"le e grava em transacao<br/>JDBC"| db
    engine -->|"reserva a chave antes do trabalho caro<br/>falha aberto para o banco"| redis
```

**Operador de mesa.** Único ator humano. Quer três coisas, nesta ordem: simular o valor presente sem efeito colateral (`POST /api/v1/simulations`), liquidar exatamente uma vez (`POST /api/v1/settlements` com `Idempotency-Key`) e conferir o que foi pago (`GET /api/v1/settlements/statement`). Não há frontend nesta entrega: a interface do operador é a API documentada em Swagger UI. Relação de confiança: o operador é autenticado fora do escopo desta entrega, mas **não é confiável quanto a repetição** — duplo clique e retry de rede são certeza, e é por isso que a chave de idempotência é header obrigatório em vez de campo opcional do corpo.

**SRM Credit Engine.** Dono das duas invariantes que o desafio cobra: o número (valor presente, deságio, conversão ao centavo) e o pagamento único (atômico, idempotente, auditável). Guarda a cotação efetivamente aplicada dentro do registro de liquidação, em vez de referenciá-la, para que o extrato nunca reinterprete "qual taxa seria a vigente".

**Provedor externo de cotação.** Hoje é `MockExternalFxRateSource`, criado condicionalmente em `FxConfiguration` quando `credit-engine.fx.upstream.enabled` é `true`. O mock é **explicitamente um mock** — com latência e instabilidade configuráveis em `application.yml` — e existe para tornar timeout, retry e disjuntor demonstráveis sem depender de rede real. O ponto de troca é a porta **`ExternalFxRateSource`**: substituir por PTAX, mesa ou agregador de mercado é escrever um adapter em `infrastructure/fx` e mudar um `@Bean`; nem o domínio nem `SettlementService` são tocados. Esta é a **única dependência que pode cair sem que a culpa seja nossa**, e por isso é a única cuja falha é traduzida em **503 `FX_RATE_UNAVAILABLE`**: sem taxa confiável, a liquidação cross-currency não assume 1:1, não usa taxa velha e não inventa default — falha e devolve a decisão ao operador.

**PostgreSQL.** Não é "onde os dados ficam": é **coguardião das invariantes**. `uk_settlements_receivable`, `uk_settlements_idempotency_key`, a coluna `version` de `receivables` e o gatilho `trg_settlements_immutable` continuam valendo com duas instâncias da aplicação no ar, ou com alguém rodando SQL na mão. Relação de falha: banco indisponível é **indisponibilidade nossa** — `ApiExceptionHandler` traduz `DataAccessException` em **503 `PERSISTENCE_UNAVAILABLE`** com a orientação de repetir a requisição com a mesma `Idempotency-Key`, que é exatamente o que torna o retry seguro.

**Redis.** A única dependência do desenho cuja queda **não muda a resposta ao cliente**, apenas o custo dela. Guarda a reserva da `Idempotency-Key` (`PROCESSING` → `COMPLETED`) para que duplo clique e retry sejam respondidos antes de resolver câmbio, precificar e tomar conexão do pool. Relação de falha declarada: **falha aberto** — `RedisIdempotencyStore` traduz qualquer `DataAccessException` em `UNAVAILABLE` e a decisão volta para dentro da transação, onde o `SELECT` por `idempotency_key` e os `UNIQUE` sempre estiveram. É por isso que o health indicator do Redis está desligado: marcar `DOWN` tiraria do balanceador um pod que liquida corretamente, transformando a **proteção** em causa de indisponibilidade. Limite e custo da decisão em `DECISIONS.md`, seção 5.

O pipeline de CI (`.github/workflows/ci.yml`) **não aparece aqui**: é ferramenta do processo de entrega, não ator que consome ou serve o sistema em execução. Colocá-lo no C1 confundiria "quem usa o sistema" com "como o sistema é construído".

---

### Nível 2 — Containers

```mermaid
flowchart TB
    operador["Operador de mesa<br/>cliente HTTP"]

    subgraph sistema["SRM Credit Engine"]
        app["Aplicacao Spring Boot 3.3<br/>JVM 21, imagem multi-stage<br/>REST + OpenAPI + Actuator<br/>porta HTTP 8080"]
        db[("PostgreSQL 16<br/>Flyway aplica V1__initial_schema.sql no start<br/>porta JDBC 5432")]
        redis[("Redis 7<br/>appendonly yes, TTL 30s e 24h<br/>porta 6379")]
    end

    fx["Provedor externo de cotacao<br/>mock in-process hoje<br/>porta ExternalFxRateSource"]

    operador -->|"HTTP 8080<br/>api/v1, swagger-ui.html, v3/api-docs"| app
    operador -->|"HTTP 8080<br/>actuator health, metrics, prometheus"| app
    app -->|"JDBC 5432<br/>DB_URL, DB_USERNAME, DB_PASSWORD"| db
    app -->|"SET NX PX antes da transacao<br/>REDIS_HOST, timeout 200ms, falha aberto"| redis
    app -->|"consulta protegida por timeout, retry e disjuntor<br/>CREDIT_ENGINE_FX_UPSTREAM_ENABLED"| fx
```

No `docker-compose.yml` são três containers de verdade — `app`, `db` e `redis` — e o provedor externo é desenhado fora da fronteira de propósito: hoje ele executa **dentro do processo** da aplicação, mas o contrato é o de um terceiro remoto, e desenhá-lo como parte da aplicação esconderia justamente o risco que a resiliência existe para tratar. O serviço `app` só sobe depois dos `healthcheck` de `db` (`pg_isready`) e `redis` (`redis-cli ping`) — no banco isso é necessário, senão o Flyway falha na primeira tentativa e o container reinicia em loop; no Redis é **conveniência de demonstração**, não dependência: `docker compose stop redis` deixa o sistema liquidando, apenas mais caro.

| Container | Responsabilidade | Tecnologia | Escala e falha |
|-----------|------------------|------------|----------------|
| `app` | Contrato HTTP, orquestração dos casos de uso, motor de precificação, fronteira transacional, resiliência do câmbio, métricas de negócio, resolução segura de credenciais | Java 21, Spring Boot 3.3, Spring Cloud AWS Secrets Manager, Spring Data JPA para entidades e extratos, springdoc, Micrometer, resilience4j; Dockerfile multi-stage com usuário sem privilégio e `ExitOnOutOfMemoryError` | **Stateless** — escala horizontal atrás de balanceador, sem sessão nem cache local de estado. Credenciais críticas (banco de dados/Redis) são obtidas de forma segura via AWS Secrets Manager em produção (com fail-safe opcional em desenvolvimento/testes locais). Nenhuma garantia depende de haver uma única instância: idempotência e unicidade moram no banco. Instância que cai é substituída; requisição em voo é desfeita pela transação e o cliente repete com a mesma `Idempotency-Key`. Pool Hikari de 10 conexões com `connection-timeout` de 3s, pequeno de propósito para que contenção apareça em vez de se esconder. |
| `db` | Persistência e **invariantes**: unicidade, checks, `version` para optimistic locking, gatilho de imutabilidade, índices do extrato | PostgreSQL 16, `NUMERIC(19,2)` para valores e `NUMERIC(19,6)` para taxas, Flyway | **Escala vertical + réplica de leitura** seria o próximo passo natural — o extrato é o único consumidor pesado de leitura e já está isolado atrás de `SettlementStatementQuery`. Ponto único de falha assumido: banco fora do ar é o sistema fora do ar, com **503 `PERSISTENCE_UNAVAILABLE`** em vez de resposta parcial. Não existe caminho de escrita que contorne o banco. |
| `redis` | Reserva da `Idempotency-Key` na borda: barra duplo clique e retry **antes** de câmbio, precificação e transação | Redis 7 com `appendonly yes` / `appendfsync everysec`; cliente Lettuce com `timeout` e `connect-timeout` de 200ms; script Lua para compensar a reserva apenas do próprio dono | **Falha aberto, por design.** Fora do ar, lento ou desligado (`credit-engine.idempotency.enabled=false`), a decisão volta para dentro da transação e **nenhuma garantia se perde** — só o custo sobe (mais um `SELECT` e uma conexão de pool por requisição). Health indicator **desligado** de propósito; o que se observa é `credit_engine.idempotency{result=unavailable}`. Chave perdida em failover custa trabalho repetido, nunca pagamento duplicado: os `UNIQUE` estão no `db`. |
| Provedor de cotação | Cotação do par quando não há taxa vigente e fresca no histórico | `ExternalFxRateSource`; hoje `MockExternalFxRateSource`, ligado só no compose via `CREDIT_ENGINE_FX_UPSTREAM_ENABLED=true` | **Não escala por nós** — é de terceiro. Fora do ar: o disjuntor `fx-upstream` abre, a liquidação cross-currency **sem taxa fresca** falha com 503 e o `FxUpstreamHealthIndicator` publica `DEGRADED` — não `DOWN`, porque liquidação em moeda única e cross-currency com taxa fresca continuam funcionando e marcar a aplicação como fora do ar faria o orquestrador derrubar instância saudável. Desligado, `fxRateProvider` é apenas `StoredFxRateProvider` sobre o histórico do banco. |

Detalhes dos parâmetros de resiliência (`call-timeout` 800ms, `max-attempts` 3, janela do disjuntor, pool sem fila) estão no `README.md`, seção *Resiliência do câmbio*.

O que atravessa esses containers e não aparece como caixa: o **rastro da requisição**. `CorrelationIdFilter` abre cada requisição com um `correlationId` (aceito do chamador via `X-Correlation-Id` ou gerado) e a `Idempotency-Key` no MDC, e o `logback-spring.xml` os emite como campo do JSON em `stdout`. É o que permite, num incidente, reconstituir uma tentativa de pagamento inteira — a pergunta que o Anexo B faz — sem cruzar log por horário. Com o guarda de idempotência no caminho, esse rastro passou a ser a única forma de distinguir, na mesma chave, quem reservou, quem foi recusado em voo e quem commitou.

---

### Modelo de dados — diagrama ER

Quatro tabelas, criadas por `V1__initial_schema.sql` e aplicadas pelo Flyway na subida. O desenho mostra **as chaves e as restrições que sustentam as invariantes** — o que está aqui é o que o banco garante mesmo com duas instâncias da aplicação no ar ou com alguém rodando SQL na mão.

```mermaid
erDiagram
    ASSIGNORS ||--o{ RECEIVABLES : "cede"
    ASSIGNORS ||--o{ SETTLEMENTS : "recebe pagamento"
    RECEIVABLES ||--o| SETTLEMENTS : "liquidado exatamente uma vez"
    FX_RATES }o..o| SETTLEMENTS : "cotacao copiada, nao referenciada"

    ASSIGNORS {
        uuid id PK
        varchar document "UK uk_assignors_document, CNPJ/CPF so digitos"
        varchar legal_name
        timestamptz created_at
    }

    RECEIVABLES {
        uuid id PK
        uuid assignor_id FK "ix_receivables_assignor"
        varchar receivable_type "CHECK DUPLICATA_MERCANTIL ou CHEQUE_PRE_DATADO"
        numeric face_value "19,2 CHECK maior que zero"
        char face_currency "CHECK BRL ou USD"
        date due_date
        varchar status "CHECK PENDING SETTLED CANCELLED"
        bigint version "optimistic locking"
        timestamptz created_at
    }

    FX_RATES {
        uuid id PK
        char base_currency "UK do par com effective_at"
        char quote_currency "CHECK diferente de base_currency"
        numeric rate "19,6 CHECK maior que zero"
        timestamptz effective_at "vigencia, indice DESC do par"
        varchar source
        timestamptz created_at
    }

    SETTLEMENTS {
        uuid id PK
        uuid receivable_id FK "UK uk_settlements_receivable"
        uuid assignor_id FK "ix_settlements_assignor_settled_at"
        varchar idempotency_key "UK uk_settlements_idempotency_key"
        char request_fingerprint "hash do corpo da requisicao"
        integer term_months "CHECK maior ou igual a zero"
        numeric monthly_base_rate "19,6 taxa base aplicada"
        numeric monthly_spread "19,6 spread da strategy"
        numeric face_value "19,2"
        char face_currency
        numeric present_value "19,2"
        numeric discount_amount "19,2"
        numeric settlement_amount "19,2"
        char settlement_currency "ix_settlements_currency_settled_at"
        numeric fx_rate "19,6 obrigatoria em cross-currency"
        char fx_base_currency
        char fx_quote_currency
        timestamptz fx_rate_effective_at
        varchar fx_rate_source
        timestamptz settled_at "ix_settlements_settled_at DESC"
    }
```

**Normalização até onde ela ajuda, e o desvio deliberado.** `assignors`, `receivables` e `fx_rates` estão em 3FN: nenhum atributo depende de outro que não seja a chave, e cedente não é texto repetido dentro do recebível. `settlements` **quebra isso de propósito**: `assignor_id`, `face_value`, `face_currency`, `term_months`, as taxas aplicadas e a cotação (`fx_rate`, `fx_base_currency`, `fx_quote_currency`, `fx_rate_effective_at`, `fx_rate_source`) são **copiados**, não referenciados. A razão é auditoria, não desempenho: um registro de liquidação precisa continuar reproduzível em 2030, quando a tabela de cotações tiver mil linhas novas e a taxa base tiver mudado três vezes. Se `settlements` apontasse para `fx_rates` por FK, o extrato de hoje reinterpretaria "qual taxa seria a vigente" — e é por isso que a relação com `FX_RATES` aparece no diagrama como **linha tracejada**: é linhagem do dado, não chave estrangeira.

**As três cardinalidades que são regra de negócio, não desenho:**

- **`receivables` 1—0..1 `settlements`**: `uk_settlements_receivable` torna o pagamento duplicado *impossível no banco*, não apenas improvável na aplicação. É a garantia que o teste de 8 threads concorrentes exercita.
- **`assignors` 1—N `settlements`**: caminho direto do extrato analítico por cedente, apoiado em `ix_settlements_assignor_settled_at`. Chegar ao cedente via `receivables` exigiria `JOIN` no relatório mais lido do sistema.
- **`idempotency_key` única em `settlements`**: idempotência tem última instância no banco. O Redis decide antes e mais barato; o `UNIQUE` decide de fato — chave perdida em failover custa trabalho repetido, nunca pagamento duplicado.

**O que não é coluna e ainda assim mora no schema.** `ck_settlements_fx_required_when_cross_currency` recusa liquidação em moeda diferente da face sem cotação completa registrada — cross-currency sem taxa auditável não entra. E `trg_settlements_immutable` barra `UPDATE` e `DELETE` na tabela: correção se faz por lançamento compensatório (estorno), porque "alterar uma liquidação registrada não é uma operação do sistema". Nenhuma das duas depende de a aplicação se comportar bem.

**Ausências deliberadas.** Não há tabela de idempotência separada (a chave é coluna única da própria liquidação — uma escrita a menos na transação de dinheiro), não há tabela de usuários (autenticação está fora desta entrega) e não há tabela de estorno: ela é o primeiro item que entra quando existir política de cancelamento, e o gatilho de imutabilidade já força esse caminho em vez do `UPDATE` silencioso.

---

### Recorte de componentes da aplicação

Quatro anéis de dependência, com a seta sempre apontando para dentro — `api` → `application` → `domain` ← `infrastructure`:

```mermaid
flowchart TB
    api["api<br/>controllers, dto, ApiExceptionHandler"]
    application["application<br/>assignor, receivable, pricing,<br/>settlement, statement, fx"]
    domain["domain<br/>money, pricing, fx,<br/>receivable, settlement, assignor"]
    infrastructure["infrastructure<br/>persistence JPA, fx, idempotency"]
    config["config<br/>CoreConfiguration, FxConfiguration,<br/>PricingConfiguration, IdempotencyConfiguration,<br/>OpenApiConfiguration"]

    api --> application
    application --> domain
    infrastructure --> domain
    config -.->|"monta e injeta os beans"| application
    config -.->|"monta e injeta os beans"| infrastructure
    api -.->|"atalho do extrato analitico<br/>item 4.1.7 do enunciado"| application
```

**(a) O domínio não tem anotação de framework.** `domain` é Java puro: `Money` sobre `BigDecimal`, `RoundingPolicy`, `PricingEngine` com as strategies, `Receivable`, `Settlement`, `FxRate` e as exceções de domínio. Nada de `@Service`, `@Component`, `@Entity` ou `@Transactional` ali dentro. A ligação com o Spring vive em **`config`**: `CoreConfiguration` publica o `Clock` (injetado em vez de `Instant.now()` espalhado, para que o timestamp seja auditável e o teste possa fixar o tempo), `PricingConfiguration` monta o motor a partir de `credit-engine.pricing.*` e `FxConfiguration` compõe a cadeia de câmbio. Consequência prática na defesa: `PricingEngineTest`, `TermCalculatorTest`, `MoneyTest` e `ResilientFxRateProviderTest` rodam **sem contexto Spring e sem banco**.

**(b) Portas declaradas do lado de dentro, implementadas do lado de fora.** Nomes conforme o código:

| Porta | Onde é declarada | Implementação |
|-------|------------------|---------------|
| `ReceivableRepository` | `domain.receivable` | `JpaReceivableRepository` |
| `SettlementRepository` | `domain.settlement` | `JpaSettlementRepository` |
| `AssignorRepository` | `domain.assignor` | `JpaAssignorRepository` |
| `FxRateRepository` | `domain.fx` | `JpaFxRateRepository` |
| `FxRateProvider` | `domain.fx` | `StoredFxRateProvider` e `ResilientFxRateProvider` |
| `BaseRateProvider` | `domain.pricing` | `FixedBaseRateProvider` |
| `ExternalFxRateSource` | `infrastructure.fx` | `MockExternalFxRateSource` |
| `FxRateWriter` | `infrastructure.fx` | `TransactionalFxRateWriter` |
| `SettlementStatementQuery` | `application.statement` | `JpaSettlementStatementQuery` |
| `IdempotencyStore` | `domain.settlement` | `RedisIdempotencyStore` e `DisabledIdempotencyStore` |

Três assimetrias são deliberadas. `ExternalFxRateSource` e `FxRateWriter` vivem em `infrastructure.fx`, não no domínio: o domínio conhece **`FxRateProvider`** — "de onde vem a taxa" com contrato de ou devolver cotação válida ou falhar — e não precisa saber que existe um terceiro na rede nem que a gravação da cotação trazida acontece em transação própria (`REQUIRES_NEW`, para sobreviver ao rollback da liquidação). `SettlementStatementQuery` fica em `application.statement` porque o extrato não carrega agregado de domínio nem participa de transação de negócio.

`IdempotencyStore`, por outro lado, **fica no domínio** mesmo sendo satisfeita por Redis — e a razão é que o contrato dela é regra, não infraestrutura: os cinco vereditos possíveis (`ACQUIRED`, `IN_PROGRESS`, `COMPLETED`, `CONFLICT`, `UNAVAILABLE`) são decisões sobre repetição de pagamento, e um deles — *"o guarda não respondeu, siga pelo banco"* — só é auditável se estiver **no tipo**, em vez de escondido num `catch` do adaptador. Nada em `domain.settlement` menciona Redis.

**(c) O atalho consciente do extrato analítico.** `SettlementStatementController` chama diretamente a porta de leitura `SettlementStatementQuery`, cuja implementação é JPA/JPQL com paginação e totais calculados no banco, apoiada nos índices `ix_settlements_settled_at`, `ix_settlements_assignor_settled_at` e `ix_settlements_currency_settled_at`. Não há service de aplicação no meio: ele só repassaria a chamada. O item **4.1.7** do enunciado autoriza consulta direta para o extrato; camada vazia é acoplamento sem benefício, e o critério para ela deixar de ser vazia é claro — no dia em que o extrato ganhar regra própria, como redação de valores por perfil de acesso, o service passa a existir porque terá o que fazer.

---

### Fluxo 1 — Liquidação cross-currency bem-sucedida

```mermaid
sequenceDiagram
    autonumber
    participant Cli as Cliente HTTP
    participant Ctl as SettlementController
    participant Svc as SettlementService
    participant Idem as IdempotencyStore Redis
    participant Tx as SettlementTransaction
    participant SetRepo as SettlementRepository
    participant RecRepo as ReceivableRepository
    participant Fx as FxRateProvider
    participant Eng as PricingEngine
    participant DB as PostgreSQL

    Cli->>Ctl: POST api/v1/settlements + Idempotency-Key
    Ctl->>Svc: settle do comando com fingerprint do pedido

    Svc->>Idem: reserve da chave com o fingerprint
    Idem->>Idem: SET NX PX PROCESSING com TTL de 30s
    Idem-->>Svc: ACQUIRED — esta requisicao e a dona do trabalho
    Note over Svc,Idem: fora da transacao: nenhuma conexao de pool tomada ainda

    Svc->>Tx: execute com a chave ja garantida na borda

    rect rgb(238, 238, 238)
        Note over Tx,DB: Transactional — inicio da transacao
        Note over Tx,SetRepo: SELECT por idempotency_key dispensado:<br/>a reserva na borda ja garantiu a unicidade da chave
        Tx->>RecRepo: findById do recebivel
        RecRepo->>DB: SELECT por id
        DB-->>RecRepo: recebivel PENDING, version 0
        Tx->>Tx: receivable.settled — elegibilidade so a partir de PENDING
        Tx->>Fx: rateFor do par no instante da liquidacao
        Fx->>DB: SELECT cotacao vigente mais recente
        DB-->>Fx: taxa vigente e fresca
        Fx-->>Tx: FxRate congelada para este calculo
        Tx->>Eng: price do input cross-currency
        Eng-->>Tx: PricingResult com VP, desagio e valor na moeda de liquidacao
        Tx->>RecRepo: settle com optimistic locking
        RecRepo->>DB: UPDATE receivables SET status SETTLED, version igual version mais um WHERE id e version
        DB-->>RecRepo: uma linha afetada
        Tx->>SetRepo: save da liquidacao
        SetRepo->>DB: INSERT em settlements com taxa, fonte e vigencia copiadas
        DB-->>SetRepo: ok, unicidade por recebivel e por chave respeitada
        Note over Tx,DB: commit — fim da transacao
    end

    Tx-->>Svc: SettlementOutcome created
    Svc->>Idem: complete com o id da liquidacao
    Idem->>Idem: SET COMPLETED com TTL de 24h — so depois do commit
    Svc-->>Ctl: SettlementOutcome created
    Ctl-->>Cli: 201 com header Location e replayed false
```

Quatro pontos que a defesa costuma cobrar. **A ordem das etapas é deliberada**: idempotência primeiro — e agora *fora* da transação, porque retry e duplo clique não devem custar recálculo, chamada ao provedor de câmbio nem conexão do pool; a cotação é resolvida **uma vez** e é a mesma que vai para a auditoria. **O `UPDATE` e o `INSERT` estão na mesma transação** — ou o recebível é marcado e a liquidação é gravada, ou nada acontece; o Anexo A fazia os dois fora de transação, com `catch` vazio, e deixava estado inconsistente de propósito. **A conclusão em Redis é escrita depois do commit** (passo final do diagrama): o que fica no guarda é resultado consumado, nunca intenção — e se a transação tivesse falhado, a reserva seria compensada em vez de publicada. **Métrica**: cada desfecho incrementa `credit_engine.settlements` com `result` em `created`, `replayed`, `idempotency_conflict`, `concurrent_conflict`, `in_progress` ou `phantom_completion`, o guarda publica `credit_engine.idempotency{store,result}`, e o motor é cronometrado em `credit_engine.pricing.duration`.

Variações da mesma chave, todas decididas **antes** da transação quando o Redis está de pé: mesma chave e mesmo corpo já concluído → **200** com a liquidação relida de `settlements` (`replayed`); mesma chave com corpo diferente → **409 `IDEMPOTENCY_KEY_CONFLICT`**; mesma chave e mesmo corpo ainda em execução → **409 `SETTLEMENT_IN_PROGRESS`**, o único 409 do sistema em que repetir resolve. Com o Redis fora do ar, todas essas decisões voltam para dentro da transação, pelo `SELECT` em `idempotency_key` — mais caro, mesmo resultado.

---

### Fluxo 2 — Provedor de cotação fora do ar durante a liquidação

```mermaid
sequenceDiagram
    autonumber
    participant Cli as Cliente HTTP
    participant Ctl as SettlementController
    participant Svc as SettlementService
    participant Res as ResilientFxRateProvider
    participant Sto as StoredFxRateProvider
    participant Up as ExternalFxRateSource
    participant DB as PostgreSQL
    participant Eh as ApiExceptionHandler

    Cli->>Ctl: POST api/v1/settlements + Idempotency-Key
    Ctl->>Svc: settle do comando

    Svc->>Svc: reserva PROCESSING no guarda de idempotencia

    rect rgb(238, 238, 238)
        Note over Svc,DB: Transactional — inicio da transacao
        Svc->>DB: recebivel PENDING
        Svc->>Res: rateFor do par
        Res->>Sto: caminho rapido no historico
        Sto->>DB: SELECT cotacao vigente do par
        DB-->>Sto: sem cotacao ou cotacao mais velha que max-staleness
        Sto-->>Res: FxRateUnavailableException

        loop tres tentativas com backoff de 100ms
            Res->>Up: fetch do par com timeout de 800ms por tentativa
            Up-->>Res: falha tecnica ou estouro de tempo
        end
        Note over Res: disjuntor fx-upstream registra uma observacao por requisicao,<br/>nao uma por tentativa

        Res->>Res: contador credit_engine.fx.lookups com outcome upstream_failed
        Res-->>Svc: FxRateUnavailableException com a causa encadeada
        Note over Svc,DB: rollback — nada gravado
    end

    Svc->>Svc: release — compensa a reserva no guarda de idempotencia
    Svc-->>Ctl: propaga a excecao de dominio
    Ctl->>Eh: tratamento centralizado
    Eh-->>Cli: 503 problem+json com errorCode FX_RATE_UNAVAILABLE
```

**O que sobra no banco depois disso: nada.** `SettlementServiceIntegrationTest.rollsBackWhenFxIsUnavailable` verifica, em PostgreSQL real, que nenhuma linha entrou em `settlements` e que o recebível continua `PENDING` com `version` **0** — a busca da cotação acontece dentro da transação da liquidação, então a exceção desfaz tudo. O cliente pode repetir com a **mesma** `Idempotency-Key` sem risco: não houve liquidação para replicar nem versão consumida.

**E o que sobra no Redis: também nada** — e esse é o passo que a troca do guarda acrescentou a este fluxo. A reserva é compensada por script Lua que só apaga o valor desta requisição. Sem isso, o 503 de câmbio deixaria a chave reservada **sem pagamento nenhum**, e o retry legítimo receberia "duplicado" para dinheiro que nunca saiu — prejuízo silencioso, que é exatamente o modo de falha que o sistema recusa em todos os outros pontos. `SettlementIdempotencyRedisIntegrationTest.releasesReservationWhenTransactionRollsBack` prova o ciclo inteiro: falha, chave ausente no Redis, cotação registrada, mesma chave liquidando normalmente.

**Por que 503 e não 200 com taxa antiga.** `FxRateUnavailableException.errorCode()` devolve `FX_RATE_UNAVAILABLE`, e `ApiExceptionHandler.statusFor` mapeia essa exceção para `SERVICE_UNAVAILABLE` — o corpo sai como `application/problem+json` com `errorCode` estável. 503 comunica "dependência fora do ar, vale tentar de novo"; devolver taxa defasada comunicaria "está tudo bem" e transferiria prejuízo silencioso para o fundo ou para o cedente.

**Variações do mesmo caminho**, todas separadas por tag da métrica `credit_engine.fx.lookups`: disjuntor já aberto falha rápido sem tocar no terceiro (`circuit_open`); pool dedicado saturado recusa em vez de enfileirar (`rejected`, ignorado pelo disjuntor e pelo retry, porque capacidade nossa não é falha do terceiro); terceiro que responde `Optional.empty()` — "não coto esse par" — não gera retry nem conta para o disjuntor (`pair_unknown`) e propaga o erro original. `ResilientFxRateProviderTest` cobre cada uma sem Spring e sem banco, incluindo o caso que mais importa para disponibilidade: **disjuntor aberto não bloqueia a liquidação quando há taxa fresca em casa**.

---

### Decisões visíveis no diagrama

- **Monólito modular, não serviços.** Um único container de aplicação com fronteiras internas explícitas (`api`, `application`, `domain`, `infrastructure`). Para este tamanho de problema — um caso de uso crítico, um banco, um terceiro — dividir custaria coordenação distribuída sem nenhum ganho de escala ou de isolamento de falha. Critério de quando valeria dividir: quando o extrato analítico passar a competir por recursos com a liquidação, ou quando um domínio novo tiver ciclo de release e time próprios. O corte estaria pronto e é visível no C2: extrair leitura por trás de `SettlementStatementQuery` é mudar um adapter.
- **Uma transação no banco, não coordenação distribuída.** Marcar o recebível e gravar a liquidação estão na mesma `@Transactional`. Saga, outbox ou two-phase commit resolveriam um problema que este desenho simplesmente não tem: as duas escritas moram no mesmo PostgreSQL. A complexidade distribuída entraria no dia em que o pagamento efetivo virasse chamada a um sistema externo de tesouraria — aí o `INSERT` deixa de ser o fim da história. As alternativas com fila e evento (SQS, SQS FIFO, Kafka, cache de leitura) foram avaliadas uma a uma em [`EDA.md`](EDA.md), com a decisão de manter as escritas síncronas e os gatilhos observáveis que reabririam a discussão.
- **Banco como coguardião das invariantes, não só a aplicação.** `uk_settlements_receivable` garante pagamento único por recebível, `uk_settlements_idempotency_key` garante chave única, `version` sustenta o optimistic locking (`UPDATE ... WHERE id = ? AND version = ?`) e `trg_settlements_immutable` barra `UPDATE` e `DELETE` em `settlements`. Validação em memória não sobrevive a duas instâncias no ar; `settlementRecordIsImmutableInTheDatabase` e o teste de **8 threads com exatamente 1 pagamento** provam as duas garantias onde elas realmente valem.
- **Redis decide antes; o banco decide de fato.** O guarda de idempotência está no caminho de dinheiro como *otimização*, não como autoridade: reserva em Redis e `INSERT` no PostgreSQL são dois sistemas sem commit em duas fases, e a resposta a isso foi **assumir o furo** em vez de encobri-lo — falha aberto, reserva compensada no rollback, conclusão publicada só pós-commit, e valor de dinheiro sempre relido de `settlements`. O critério para o Redis virar autoridade (remover os `UNIQUE`) não existe: chave perdida em failover viraria pagamento duplicado. `DECISIONS.md` seção 5 registra o que isso comprou e o que custou.
- **Terceiro atrás de porta para poder ser trocado sem tocar no domínio.** `ExternalFxRateSource` separa "quem cota" de "como a liquidação usa a cotação". O domínio vê `FxRateProvider` e um contrato binário: cotação válida ou exceção. Trocar mock por provedor real, ou empilhar dois provedores com prioridade, é adapter novo mais uma linha em `FxConfiguration`.
- **Resiliência composta programaticamente, não anotada.** Timeout por dentro, retry no meio, disjuntor por fora, escrito à mão em `FxConfiguration` com os módulos do resilience4j, sem starter e sem AOP. A ordem dos guarda-corpos é a diferença entre uma requisição ruim contar como **uma** observação no disjuntor ou como três — com anotação essa ordem fica implícita na precedência dos aspectos, e "por que o disjuntor abriu numa única requisição" viraria arqueologia.
- **Ausência deliberada de cache de cotação.** Não existe camada de cache no diagrama, e o histórico em `fx_rates` só serve como caminho rápido enquanto a taxa está dentro de `credit-engine.fx.max-staleness` (default 12h). Taxa velha em dia de volatilidade não é degradação graciosa: é prejuízo silencioso. O sistema prefere recusar a liquidação a acertar por sorte.
- **`DEGRADED` em vez de `DOWN` no health.** `FxUpstreamHealthIndicator` reporta disjuntor aberto como degradação, não como queda. Marcar a aplicação como fora do ar faria o orquestrador reiniciar ou tirar do balanceador uma instância que continua liquidando em moeda única e cross-currency com taxa fresca — transformando problema de terceiro em indisponibilidade própria.

---

### Limites do diagrama

- **Não há nível 4 (código).** Classe por classe, com assinatura e dependência, é o que o código já mostra — e com a vantagem de nunca estar desatualizado. Diagrama de código envelhece no primeiro refactor e passa a mentir; o recorte de componentes acima vai até onde o desenho ainda explica decisão, e daí em diante a fonte é `src/main/java`.
- **Não há frontend.** Escopo backend por decisão consciente, registrada no `README.md` e no `SPEC.md`: o enunciado prevê painel e grid, e a prioridade aqui foi o caminho de dinheiro (motor ao centavo, ACID, concorrência, contrato HTTP, evidência de teste). Desenhar um container de UI que não existe seria enfeitar o diagrama com trabalho não entregue.
- **Não há topologia de produção.** Nada de multi-região, réplicas, balanceador, service mesh, gateway ou observabilidade centralizada. O que existe de verdade é o `docker-compose.yml` com três serviços, e é isso que o C2 mostra — inclusive o Redis como container único, sem Sentinel nem Cluster: em produção ele teria réplica, e o desenho já assume que a réplica pode perder escrita (é por isso que ele não é autoridade). As propriedades que **permitem** escalar estão afirmadas onde são verificáveis — aplicação stateless, invariantes no banco, health separando degradação de queda — mas desenhar a topologia seria especulação sobre requisitos de carga, SLA e custo que ninguém mediu.
- **Não há fronteira de segurança.** Autenticação, autorização e trilha por usuário estão fora desta entrega; o `management.endpoint.health.show-details` já está em `when-authorized`, mas não existe provedor de identidade a desenhar. Caixa de "Auth" sem implementação por trás seria a pior espécie de diagrama: a que promete.
- **Não há fluxo de simulação nem de extrato em sequência.** Os dois estão documentados no `README.md` com `curl` executável e cobertos por `CreditEngineApiIntegrationTest` e `SettlementStatementQueryIntegrationTest`. Os dois diagramas de sequência daqui são os que envolvem transação, concorrência e falha de terceiro — onde a forma do desenho realmente muda o entendimento.
- **Não há broker, tópico nem read model.** O fluxo com outbox, eventos e projeção de leitura existe apenas como proposta em `EDA.md` e **não** está implementado: desenhá-lo aqui, junto dos containers reais, seria misturar o que roda com o que foi só discutido.
