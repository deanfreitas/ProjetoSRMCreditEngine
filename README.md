# SRM Credit Engine

Backend de precificação e liquidação de recebíveis com caixa multimoedas (BRL/USD). Recebe um lote de títulos (duplicata mercantil, cheque pré-datado), calcula o valor presente com deságio por tipo de ativo, converte câmbio quando o cedente recebe em moeda distinta da face e grava a liquidação de forma atômica, idempotente e auditável.

**Entrega somente backend** — decisão consciente de escopo (nível sênior do desafio). O enunciado prevê painel/grid no frontend; aqui a prioridade foi o caminho de dinheiro: motor ao centavo, ACID, concorrência, contrato HTTP e evidência de teste. A API OpenAPI é a interface do operador nesta entrega. Premissas de negócio e precisão estão em [`SPEC.md`](SPEC.md) — este README remete a elas, não as repete.

---

### Como rodar

Requisitos: **JDK 21**, **Maven 3.9+**, **Docker** (Compose e Testcontainers).

#### (a) Tudo via Docker Compose

Sobe PostgreSQL 16 (healthcheck com `pg_isready`), Redis 7 (`appendonly yes`, healthcheck com `redis-cli ping`) e a aplicação (imagem multi-stage Java 21). A app só sobe depois dos dois saudáveis — mas **não depende do Redis para funcionar**: o guarda de idempotência falha aberto para o PostgreSQL (ver §Idempotência e concorrência).

```bash
docker compose up --build
```

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

Variáveis injetadas no serviço `app` (ver `docker-compose.yml`):

| Variável       | Valor no compose                                      |
|----------------|--------------------------------------------------------|
| `DB_URL`       | `jdbc:postgresql://db:5432/srm_credit_engine`          |
| `DB_USERNAME`  | `srm`                                                  |
| `DB_PASSWORD`  | `srm`                                                  |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` (guarda de idempotência) |
| `CREDIT_ENGINE_FX_UPSTREAM_ENABLED` | `true` (liga o provedor externo mockado de cotação) |

Para demonstrar o modo degradado, basta `docker compose stop redis`: a liquidação continua correta (a decisão volta para dentro da transação) e `credit_engine.idempotency{result=unavailable}` passa a contar.

#### (b) App local + Postgres e Redis do Compose

```bash
docker compose up -d db redis
```

Com o banco em `localhost:5432` e o Redis em `localhost:6379`, os defaults de `application.yml` bastam (`DB_URL=jdbc:postgresql://localhost:5432/srm_credit_engine`, usuário/senha `srm`, `REDIS_HOST=localhost`). Flyway aplica `V1__initial_schema.sql` na subida.

Sem Redis local a aplicação sobe e liquida normalmente (degradação por design); para tirar o guarda do caminho de forma explícita, `CREDIT_ENGINE_IDEMPOTENCY_ENABLED=false`.

```bash
mvn spring-boot:run
```

Ou, explícito:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/srm_credit_engine
export DB_USERNAME=srm
export DB_PASSWORD=srm
mvn spring-boot:run
```

#### (c) Testes

```bash
mvn test
```

A suíte tem **98 testes** (invocações JUnit, incluindo `@ParameterizedTest`). Testes de integração usam **Testcontainers** (`PostgreSQL` e `Redis`) — **Docker precisa estar rodando**. H2 não entra no caminho: dialeto, `NUMERIC` e locking importam exatamente aqui; e fake de Redis em memória não provaria `SET NX`, TTL nem script Lua.

#### (d) Lint

```bash
mvn -B checkstyle:check
```

O `maven-checkstyle-plugin` também está amarrado à fase `validate` do build (`config/checkstyle/checkstyle.xml`), então `mvn test` / `mvn verify` já falham no lint antes de compilar e gastar tempo de teste.

---

### Gestão de Credenciais e Segredos (AWS Secrets Manager)

Para ambientes de produção ou homologação na AWS (ECS, EKS, EC2), as credenciais mais importantes — como as de **banco de dados** (`spring.datasource.username`, `spring.datasource.password`, `spring.datasource.url` ou `DB_USERNAME`, `DB_PASSWORD`, `DB_URL`) e Redis — são obtidas de forma segura através do **AWS Secrets Manager**, dispensando senhas em texto plano no código, no `application.yml` ou em arquivos de variáveis de ambiente.

A integração utiliza o **Spring Cloud AWS Secrets Manager** (`io.awspring.cloud:spring-cloud-aws-starter-secrets-manager`) com resolução opcional (`optional:aws-secretsmanager:...`), o que garante que em ambiente local ou de teste a aplicação continue subindo com os defaults ou variáveis de ambiente sem exigir conexão com a AWS.

#### Variáveis de configuração da AWS

| Variável | Descrição | Default |
|---|---|---|
| `AWS_SECRETS_MANAGER_ENABLED` | Habilita a integração com o AWS Secrets Manager | `false` |
| `AWS_SECRETS_NAME` | Nome/caminho do segredo no AWS Secrets Manager | `/secret/srm-credit-engine` |
| `AWS_REGION` | Região da AWS onde o segredo está armazenado | `us-east-1` |
| `AWS_ENDPOINT_URL` | Endpoint customizado da AWS (opcional, para LocalStack) | *(vazio)* |

#### Exemplo de payload JSON armazenado no AWS Secrets Manager

```json
{
  "spring.datasource.username": "srm_prod_user",
  "spring.datasource.password": "SuperSecurePassword123!",
  "spring.datasource.url": "jdbc:postgresql://aurora-cluster.srm.internal:5432/srm_credit_engine",
  "REDIS_HOST": "redis-cluster.srm.internal",
  "REDIS_PORT": "6379"
}
```

A aplicação autentica automaticamente usando a cadeia padrão de credenciais da AWS (*AWS Default Credentials Provider Chain*), permitindo autenticação nativa via **IAM Roles for Service Accounts (IRSA)** no EKS, **ECS Task Execution Role** no ECS ou **Instance Profile** no EC2.

---

### Fluxo ponta a ponta (curl)

Sequência alinhada ao golden case C3 e a `CreditEngineApiIntegrationTest.fullOperatorFlow`. Valores monetários e taxa vão como **string** no JSON (nunca number). Ajuste `DUE` para ~90 dias à frente (3 meses comerciais 30/360).

```bash
BASE=http://localhost:8080
DUE=$(date -u -v+90d +%Y-%m-%d 2>/dev/null || date -u -d '+90 days' +%Y-%m-%d)

# 1) Cedente
ASSIGNOR=$(curl -sS -X POST "$BASE/api/v1/assignors" \
  -H 'Content-Type: application/json' \
  -d '{"document":"12345678000199","legalName":"Industria Ipiranga Ltda"}')
echo "$ASSIGNOR"
# 201: id + document. Prova cadastro com CNPJ único.

ASSIGNOR_ID=$(echo "$ASSIGNOR" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

# 2) Recebível (face BRL, duplicata)
RECEIVABLE=$(curl -sS -X POST "$BASE/api/v1/receivables" \
  -H 'Content-Type: application/json' \
  -d "{\"assignorId\":\"$ASSIGNOR_ID\",\"type\":\"DUPLICATA_MERCANTIL\",\"faceValue\":\"100000.00\",\"faceCurrency\":\"BRL\",\"dueDate\":\"$DUE\"}")
echo "$RECEIVABLE"
# 201: status PENDING, faceValue.amount "100000.00". Prova ativo aberto versionado.

RECEIVABLE_ID=$(echo "$RECEIVABLE" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

# 3) Cotação USD/BRL 5.4321 (1 USD = 5.4321 BRL)
curl -sS -X POST "$BASE/api/v1/fx-rates" \
  -H 'Content-Type: application/json' \
  -d '{"baseCurrency":"USD","quoteCurrency":"BRL","rate":"5.4321","source":"MESA_OPERACOES"}'
# 201: rate "5.4321", source MESA_OPERACOES. Prova histórico append-only com vigência.

# 4) Simulação (sem efeito colateral)
curl -sS -X POST "$BASE/api/v1/simulations" \
  -H 'Content-Type: application/json' \
  -d "{\"faceValue\":\"100000.00\",\"faceCurrency\":\"BRL\",\"type\":\"DUPLICATA_MERCANTIL\",\"dueDate\":\"$DUE\",\"settlementCurrency\":\"USD\"}"
# 200: presentValue "92859.94" BRL, settlementAmount "17094.67" USD, discount "7140.06", termMonths 3.
# Prova o mesmo motor da liquidação (C3) e o rastro de taxas.

# 5) Liquidação com Idempotency-Key
BODY="{\"receivableId\":\"$RECEIVABLE_ID\",\"settlementCurrency\":\"USD\"}"
curl -sS -D - -X POST "$BASE/api/v1/settlements" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: chave-do-operador-1' \
  -d "$BODY"
# 201, replayed false, settlementAmount "17094.67" USD, fxRate congelada. Prova pagamento único + auditoria.

# 6) Replay da mesma chave e mesmo body
curl -sS -D - -X POST "$BASE/api/v1/settlements" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: chave-do-operador-1' \
  -d "$BODY"
# 200, replayed true, mesmo id de liquidação. Prova retry seguro: nada novo gravado, nada pago de novo.

# 7) Extrato com filtros
curl -sS "$BASE/api/v1/settlements/statement?assignorId=$ASSIGNOR_ID&currency=USD&page=0&size=20"
# 200: content com a liquidação, totals do filtro inteiro (não só da página). Prova JPA/JPQL + paginação server-side.
```

Documentação interativa: Swagger UI e `/v3/api-docs` (springdoc).

---

### Escolha de stack

| Escolha | Por quê |
|---------|---------|
| **Java 21 + Spring Boot 3.3** | Tipagem forte, ecossistema maduro para API financeira, `BigDecimal` nativo, validação, Actuator e transações sem reinventar infraestrutura. LTS e tooling previsível na defesa ao vivo. |
| **Spring Data JPA** | JPA (`@Entity`/`@Table`) para ciclo de vida de agregados e mutações de escrita; consultas analíticas e extratos paginados em JPA/JPQL via `EntityManager` e `@Query` (4.1.6/4.1.7). |
| **PostgreSQL 16** | `NUMERIC(19,2)` / `NUMERIC(19,6)` exatos, constraints, índices e trigger de imutabilidade. Float/real não entram. |
| **Redis 7 (Lettuce)** | Guarda de idempotência na borda: `SET NX PX` barra duplo clique e retry **antes** de resolver câmbio, precificar e tomar conexão do pool. Entra como otimização, não como autoridade — a unicidade continua no PostgreSQL. |
| **Flyway** | Schema versionado junto do código; a primeira migração já carrega as invariantes de negócio. |
| **Testcontainers** | Mesmo dialeto e locking da produção. H2 esconderia exatamente as falhas que o desafio pune. |
| **springdoc-openapi** | Contrato HTTP gerado do código — não envelhece separado da implementação. |
| **resilience4j (módulos programáticos)** | Timeout, retry e disjuntor compostos à mão em `FxConfiguration`, sem starter/AOP: a ordem dos guarda-corpos fica escrita e testável, não implícita na precedência dos aspectos. |
| **Checkstyle no build** | Regras que sustentam as afirmações do `REVIEW.md` viram gate automático na fase `validate`, não boa intenção em code review. |

Detalhes de premissa (prazo 30/360, taxa base configurável, HALF_EVEN, etc.) → [`SPEC.md`](SPEC.md).

---

### Arquitetura em camadas

Pacote base: `br.com.srm.creditengine`.

| Pacote | Responsabilidade |
|--------|------------------|
| `api` | Controllers REST, `ApiExceptionHandler` (RFC 9457 / `problem+json`), DTOs de fronteira (`dto`). |
| `application` | Casos de uso: `assignor`, `receivable`, `pricing`, `settlement`, `statement`, `fx`. Orquestra domínio + portas. |
| `domain` | Núcleo sem anotação de framework: `money`, `pricing` (Strategy + motor), `fx`, `receivable`, `settlement`, `assignor`. Regras, value objects, exceções de domínio. |
| `infrastructure` | Adaptadores: `persistence` (JPA), `fx` (`StoredFxRateProvider`, `ResilientFxRateProvider`, `ExternalFxRateSource` / `MockExternalFxRateSource`, `TransactionalFxRateWriter`, `FxUpstreamHealthIndicator`) e `idempotency` (`RedisIdempotencyStore`, `DisabledIdempotencyStore`). |
| `config` | Beans de pricing/FX/idempotência, OpenAPI, propriedades (`credit-engine.*`). |

O **domínio não depende de Spring/JPA**. O extrato (`SettlementStatementController` → `SettlementStatementQuery` / `JpaSettlementStatementQuery`) **atalha da API direto para a porta de leitura em JPA** — autorizado no item **4.1.7** do enunciado; inventar um service que só repassa a chamada seria camada vazia.

---

### Decisões de precisão e semântica financeira

Resumo; a defesa completa está no `SPEC.md` (§2 e §4).

- **`Money` sobre `BigDecimal`**: fábrica por `BigDecimal`/`String`; **não existe** construtor a partir de `double`/`float`.
- **`RoundingPolicy`**: intermediários em `MathContext.DECIMAL128`; arredondamento monetário **HALF_EVEN, 2 casas, uma única vez no final** (`Money.rounded()`). Deságio = face − VP já arredondado (invariante `face = VP + deságio`).
- **Banco**: `NUMERIC(19,2)` valores; `NUMERIC(19,6)` taxas/câmbio. Nunca `float`/`real`.
- **API**: quantias como **string** decimal nos DTOs (`MoneyView.amount`, `faceValue`, `rate`) — JSON number em JavaScript vira double e perde centavo.
- **`FxRate`**: direção explícita — *1 unidade de `baseCurrency` = `rate` unidades de `quoteCurrency`* (C3: USD/BRL 5,4321). Conversão tipada; inverter cotação é o erro clássico do Anexo A.
- **Cotação na liquidação**: resolvida **uma vez**, **congelada** no registro de auditoria (par, valor, `effectiveAt`, fonte). Cotação ausente ou defasada (`credit-engine.fx.max-staleness`, default 12h) → **503** (`FX_RATE_UNAVAILABLE`), sem fallback 1:1 ou taxa inventada. Simulação e liquidação **não compartilham** snapshot: cada uma lê a vigente no seu instante.

---

### Resiliência do câmbio

O provedor externo de cotação (aqui `MockExternalFxRateSource`, com latência e instabilidade configuráveis) é tratado como terceiro de verdade: pode demorar, cair ou simplesmente não cotar o par pedido.

**Porta com dois modos de "não deu"** (`ExternalFxRateSource`): `Optional.empty()` = o terceiro respondeu e não cota esse par (resposta válida — não gera retry nem conta para o disjuntor); `FxRateSourceException` = falha técnica (timeout, 5xx, conexão), que conta para os dois. Tratar as duas igual levaria a retentar uma pergunta cuja resposta não muda e a abrir o disjuntor por par mal configurado. O mock **não inventa** cotação de par desconhecido e **não inverte** a taxa por conta própria (defeito clássico do Anexo A).

**Caminho rápido é o banco** (`ResilientFxRateProvider`): havendo cotação vigente e fresca no histórico, o terceiro não é consultado — e é essa a razão de o disjuntor aberto **não** derrubar a operação. Quando não há taxa utilizável, a consulta externa sai com três guarda-corpos compostos **disjuntor por fora, retry no meio, timeout por dentro**:

| Guarda-corpo | Por quê |
|--------------|---------|
| **Timeout por tentativa** (`call-timeout`, 800ms) | A thread da liquidação segura a transação aberta enquanto espera; provedor lento sem timeout vira contenção no pool de conexões. |
| **Retry com backoff** (`max-attempts` 3, `retry-backoff` 100ms) | Falha de um pacote não vira erro de negócio. Retry aqui é seguro porque é consulta: não muda estado de ninguém. |
| **Disjuntor** (`fx-upstream`: janela 10, mínimo 5 chamadas, 50% de falha, 30s aberto, 2 chamadas em half-open) | Com o terceiro fora do ar, para de tentar e falha rápido; sem ele cada requisição pagaria o timeout inteiro e a fila de entrada estouraria por um sistema que não é nosso. |

A ordem importa: assim uma requisição conta como **uma** observação no disjuntor (não três, o que abriria o circuito em uma única requisição ruim) e o teto de tempo vale por tentativa. Saturação do pool dedicado (`RejectedExecutionException`) é ignorada pelo disjuntor e pelo retry — capacidade nossa não é falha do terceiro.

**Validação do que o terceiro responde**: par trocado, vigência no futuro e cotação defasada são recusados. Há tolerância de **5s de skew de relógio** (`CLOCK_SKEW_TOLERANCE`) porque a vigência devolvida é naturalmente alguns milissegundos posterior à consulta e os relógios não estão sincronizados; com folga grande, taxa agendada para o futuro entraria como se fosse de agora.

**Não existe plano B com taxa velha.** Taxa defasada em dia de volatilidade não é degradação graciosa, é prejuízo silencioso: a liquidação falha com **503** (`FX_RATE_UNAVAILABLE`) e o operador decide.

**Escrita em transação própria** (`TransactionalFxRateWriter`, `REQUIRES_NEW`): a busca acontece dentro da transação da liquidação; se gravasse junto e a liquidação fosse desfeita (optimistic locking perdido), a cotação desapareceria e a próxima tentativa bateria no terceiro outra vez, no meio de um pico. Colisão de chave única (par + vigência) **não é erro** — outra instância gravou a mesma cotação primeiro, e o histórico é append-only.

**Health** (`FxUpstreamHealthIndicator`): disjuntor aberto responde **`DEGRADED`**, não `DOWN`. Liquidação em moeda única e cross-currency com taxa fresca continuam funcionando; marcar a aplicação como fora do ar faria o orquestrador reiniciar ou tirar do balanceador uma instância saudável, transformando problema de terceiro em indisponibilidade própria.

**Composição programática** (`FxConfiguration`), com os módulos `resilience4j-circuitbreaker` / `-retry` / `-timelimiter` / `-micrometer` — **sem starter e sem AOP**: com anotação, a ordem dos aspectos fica implícita no framework e "por que o disjuntor abriu em uma única requisição" vira arqueologia. Métricas do disjuntor vão para o Micrometer via `TaggedCircuitBreakerMetrics`. O pool é **dedicado e sem fila** (`SynchronousQueue`, `AbortPolicy`, `max-concurrent-calls` 8): quando o terceiro fica lento, a rejeição aparece rápido em vez de acumular requisições esperando.

**O mock nasce desligado** (`credit-engine.fx.upstream.enabled` default `false`, junto de `credit-engine.fx.resilience` em `application.yml`) — decisão consciente: mock que sobe sozinho acaba precificando operação real. O `docker-compose.yml` liga explicitamente no ambiente de demonstração via `CREDIT_ENGINE_FX_UPSTREAM_ENABLED=true`. Desligado, `fxRateProvider` é apenas o histórico do banco (`StoredFxRateProvider`).

---

### Idempotência e concorrência

**Contrato HTTP** (`POST /api/v1/settlements`):

| Situação | Status | Corpo |
|----------|--------|--------|
| Primeira execução | **201** | liquidação nova, `replayed: false`, header `Location` |
| Mesma `Idempotency-Key` + mesmo payload | **200** | liquidação original, `replayed: true` |
| Mesma chave + payload diferente | **409** | `IDEMPOTENCY_KEY_CONFLICT` |
| Mesma chave + mesmo payload, primeira ainda em execução | **409** | `SETTLEMENT_IN_PROGRESS` (aqui repetir resolve) |
| Header ausente | **400** | `MISSING_REQUIRED_HEADER` |

`Idempotency-Key` é **obrigatório** (header, máx. 120 chars). A chave é da requisição (retry de rede), não do body.

**Quatro camadas de defesa**, nesta ordem:

1. **Guarda em Redis na borda** (`RedisIdempotencyStore`, chamado por `SettlementService` **fora** da transação) — `SET NX PX` reserva a chave em sub-milissegundo. Duplo clique e retry são respondidos aqui, sem resolver cotação, sem precificar e sem tomar conexão do pool. Estado `PROCESSING` (TTL 30s) → `COMPLETED` com o id da liquidação (TTL 24h), escrito **só depois do commit**.
2. **Regra de domínio / aplicação** (`SettlementTransaction`) — lookup pela chave quando a borda não garantiu (Redis desligado, fora do ar ou registro expirado); fingerprint do pedido; recebível só liquida a partir de `PENDING`.
3. **Optimistic locking** — `UPDATE receivables SET status='SETTLED', version=version+1 WHERE id=? AND version=? AND status='PENDING'`; zero linhas → `CONCURRENT_SETTLEMENT` (409).
4. **Unicidade no banco** — `uk_settlements_receivable`, `uk_settlements_idempotency_key`; `DuplicateKeyException` vira conflito e a transação desfaz. Trigger `trg_settlements_immutable` barrando UPDATE/DELETE em `settlements`.

As camadas 2-4 seguem na **mesma `@Transactional`**: ou marca o recebível e grava a liquidação, ou nada. Teste de corrida: **8 threads**, exatamente **1** pagamento.

**Por que o Redis não é a autoridade.** Reserva em Redis e `INSERT` no PostgreSQL são dois sistemas sem commit em duas fases, então o desenho assume isso em vez de fingir o contrário:

- **Falha aberto.** Qualquer erro do Redis degrada para o caminho do banco (`UNAVAILABLE`), nunca para 5xx. Idempotência não pode ser causa de indisponibilidade — por isso o health indicator do Redis está desligado em `application.yml`: Redis fora do ar deixa a aplicação mais lenta, não insalubre.
- **Reserva compensada no rollback.** Se a transação não commita (503 de câmbio, recebível inelegível, pod morto), a reserva é liberada por script Lua que só apaga se o valor ainda for o desta requisição. Sem isso, o retry legítimo receberia "duplicado" para um pagamento que **nunca aconteceu** — falha silenciosa, pior que indisponibilidade.
- **Valor de dinheiro nunca sai de cache.** No replay, o Redis diz *quem* é a chave; o corpo da resposta vem de `settlements`. Guarda apontando conclusão que o banco não tem → o banco manda e o caminho completo é refeito.
- **TTL não reabre risco.** Expirada a janela, o retry cai no caminho do banco e é barrado por `uk_settlements_idempotency_key`.

Tudo isso está coberto em `SettlementIdempotencyRedisIntegrationTest` (Redis real), incluindo o cenário de Redis inacessível.

---

### Contrato de erros

`ApiExceptionHandler` concentra o mapa domínio → HTTP. **Erro nunca responde 200.** 4xx → log de aviso (sem stack); 5xx → log de erro com causa. Corpo: `application/problem+json` com `errorCode` estável e `timestamp`.

| Status | Situação | Exemplo de `errorCode` |
|--------|----------|-------------------------|
| **400** | Payload/header inválido, JSON ilegível | `VALIDATION_FAILED`, `MISSING_REQUIRED_HEADER`, `MALFORMED_REQUEST`, `INVALID_PARAMETER` |
| **404** | Recurso inexistente | `RECEIVABLE_NOT_FOUND`, `ASSIGNOR_NOT_FOUND`, `SETTLEMENT_NOT_FOUND` |
| **409** | Colisão de estado / corrida / chave reusada ou em voo | `RECEIVABLE_NOT_SETTLEABLE`, `CONCURRENT_SETTLEMENT`, `IDEMPOTENCY_KEY_CONFLICT`, `SETTLEMENT_IN_PROGRESS`, `ASSIGNOR_ALREADY_REGISTERED`, `FX_RATE_ALREADY_REGISTERED` |
| **422** | Sintaxe ok, sem sentido financeiro | `INVALID_PRICING_INPUT`, `INVALID_ARGUMENT`, `MISSING_FX_RATE`, `FX_RATE_NOT_APPLICABLE` |
| **503** | Dependência indisponível (câmbio defasado/ausente, persistência) | `FX_RATE_UNAVAILABLE`, `PERSISTENCE_UNAVAILABLE` |
| **500** | Invariante interna / bug | `INTERNAL_ERROR`, `CURRENCY_MISMATCH`, `UNSUPPORTED_RECEIVABLE_TYPE` |

Nenhuma exceção é engolida: o `catch` vazio do Anexo A é o anti-padrão que este handler existe para eliminar.

---

### Observabilidade

**Métricas de negócio** (Micrometer):

- `credit_engine.settlements{result}` — contador com `result` ∈ `created` \| `replayed` \| `idempotency_conflict` \| `concurrent_conflict` \| `in_progress` \| `phantom_completion`
- `credit_engine.idempotency{store,result}` — contador do guarda com `result` ∈ `acquired` \| `in_progress` \| `replayed` \| `conflict` \| `completed` \| `released` \| `unavailable` \| `vanished` \| `complete_failed` \| `release_failed`: é onde se lê se o Redis está ajudando (`acquired`/`replayed`), se está fora do ar (`unavailable`) ou se reservas estão ficando presas (`release_failed`)
- `credit_engine.pricing.duration` — timer do motor (simulação e liquidação)
- `credit_engine.fx.lookups{outcome}` — contador de resolução de cotação com `outcome` ∈ `stored` \| `refreshed` \| `circuit_open` \| `rejected` \| `upstream_failed` \| `pair_unknown`: separa "servido pelo histórico" de "buscado no terceiro", e distingue disjuntor aberto, pool saturado, falha técnica e par não cotado
- `resilience4j_circuitbreaker_*` — estado, chamadas e taxa de falha do disjuntor `fx-upstream` (via `TaggedCircuitBreakerMetrics`): em incidente, a primeira pergunta é "o disjuntor está aberto?"

**Actuator** (`application.yml`): expostos `health`, `info`, `metrics`, `prometheus`. Com o provedor externo ligado, o `FxUpstreamHealthIndicator` publica em `/actuator/health` o nome do disjuntor, o estado e as contagens de chamadas bufferizadas/falhas.

**Log estruturado em JSON** (`logback-spring.xml`, uma linha por evento em `stdout`): `CorrelationIdFilter` põe `correlationId` e `idempotencyKey` no MDC, e o encoder os publica como **campo indexável**, não interpolados no texto. As duas perguntas de um incidente de pagamento duplicado viram filtro no agregador: *tudo desta requisição* e *todas as tentativas desta chave*.

- O `X-Correlation-Id` é aceito do chamador (o rastro atravessa a fronteira) e **sempre devolvido na resposta, inclusive em erro** — quem abre ticket com o id em mãos acha a linha exata. Valor fora do formato aceito é descartado e substituído por um gerado em casa: cabeçalho de terceiro com quebra de linha permitiria **forjar linha de log** e contaminar a investigação.
- Logs de liquidação trazem `settlementId`, `receivableId`, `assignorId`, valores, taxa e prazo — o bastante para distinguir created vs replay vs conflito.
- Para ler no terminal durante desenvolvimento ou na defesa ao vivo: `SPRING_PROFILES_ACTIVE=console`. Nos testes vale `src/test/resources/logback-test.xml`, que mantém a saída da suíte legível.

---

### Testes / evidência

**96 invocações** no total (`mvn test`). Por classe:

| Classe | # | O que prova |
|--------|---|-------------|
| `GoldenCasesTest` | 4 | C1/C2/C3 ao centavo + invariante face = VP + deságio (cálculo independente do motor) |
| `PricingEngineTest` | 19 | Bordas (prazo 0, 1 centavo, prazo longo), HALF_EVEN (empates), FX obrigatório/direção, strategies |
| `TermCalculatorTest` | 6 | 30/360, fração arredonda para cima, vencido rejeitado |
| `MoneyTest` | 4 | soma exata, moedas distintas falham, arredondamento explícito |
| `SettlementServiceIntegrationTest` | 11 | C1/C3 no banco, replay, chave conflituosa, **8 threads → 1 pagamento**, rollback se FX cai, taxa defasada/futura, **imutabilidade barrada pelo trigger** |
| `SettlementIdempotencyRedisIntegrationTest` | 6 | com Redis e PostgreSQL reais: conclusão publicada só após o commit e replay a partir dela, gêmea em voo recusada na borda (`SETTLEMENT_IN_PROGRESS`), **reserva compensada no rollback do câmbio** e retry legítimo liquidando depois, chave reusada recusada sem abrir transação, conclusão fantasma perdendo para o banco, e **Redis inacessível não derrubando a liquidação** |
| `SettlementStatementQueryIntegrationTest` | 8 | filtros (período, cedente, moeda), paginação, totais no banco, página abusiva |
| `CreditEngineApiIntegrationTest` | 9 | fluxo HTTP completo C3, 2ª liquidação 409, FX ausente 503, OpenAPI exposto, `X-Correlation-Id` em toda resposta (inclusive 404), fronteira do vencimento "hoje" |
| `CorrelationIdFilterTest` | 5 | correlação gerada e ecoada no header, id do chamador reaproveitado, chave de idempotência no contexto de log, **header forjado com quebra de linha descartado**, contexto limpo mesmo quando a requisição falha |
| `SettlementControllerTest` | 10 | contrato HTTP de status (201/200/400/404/409/503) com handler real |
| `ResilientFxRateProviderTest` | 12 | sem Spring e sem banco: caminho rápido sem tocar no terceiro, timeout cortando a espera, retry cobrindo falha passageira, disjuntor abrindo e falhando rápido sem bater no provedor, disjuntor aberto **não** bloqueando quando há taxa fresca em casa, recuperação em half-open, par desconhecido sem retry nem disjuntor, e recusa de taxa de par trocado / vigência futura / defasada, além da tolerância de skew |
| `FxUpstreamRefreshIntegrationTest` | 2 | em PostgreSQL real: cotação trazida do provedor entra no histórico e a segunda consulta é servida pelo histórico; liquidação cross-currency reproduz o golden case C3 (`US$ 17.094,67`) usando a taxa do provedor e a congela na auditoria |

Integração = PostgreSQL real via Testcontainers + schema Flyway, mais Redis real onde o guarda de idempotência está no caminho.

---

### CI e linter

`.github/workflows/ci.yml` roda em push e PR para `main`, com `concurrency` cancelando execuções anteriores da mesma ref (PR com vários pushes não faz fila de build obsoleto). Dois jobs em `ubuntu-latest`:

| Job | O que faz |
|-----|-----------|
| `lint` | `mvn -B checkstyle:check` — primeiro gate, rápido; falha aqui não gasta tempo de teste |
| `test` | `mvn -B verify` após o lint (`needs: lint`); `target/surefire-reports` sai como artefato mesmo em falha (`if: always()`) |

O runner tem **Docker nativo**, então **não há `services:` de Postgres**: o Testcontainers sobe o banco — o mesmo caminho da máquina do desenvolvedor, sem um segundo jeito de subir schema só para a CI. JDK 21 Temurin com `cache: maven`.

O linter (`config/checkstyle/checkstyle.xml`, ligado ao `pom.xml` na fase `validate`, somente código de produção) existe para transformar em gate as regras que este domínio exige:

- **`catch` vazio banido** (`EmptyCatchBlock`, sem exceção por comentário) — é exatamente o defeito do Anexo A: erro engolido em caminho de dinheiro.
- **`float`/`double` proibidos** — dinheiro não cabe em binário; o teto é `BigDecimal`.
- **Construtor de `BigDecimal` com literal numérico proibido** — `new BigDecimal(0.1)` carrega o erro do `double` para dentro do decimal exato; usa-se `String` ou `Money.of()`.
- **`IllegalCatch` para `Throwable` e `RuntimeException`** — captura genérica esconde bug de lógica. `java.lang.Exception` fica fora da lista de propósito: é capturada no limite com o mundo externo (`ResilientFxRateProvider`, em volta do `Callable`), onde não é engolida e sim traduzida em exceção de domínio com causa.
- **Teto de 140 colunas, sem arquivo de supressão** — 140 (e não 120) porque assinatura de construtor de record e SQL nomeado passam disso sem prejudicar leitura; sem supressão porque regra com exceção por pacote perde autoridade e vira ruído que o time aprende a ignorar.

---

### Estratégia de branching e histórico Git

Contexto: um autor, prazo curto, defesa ao vivo em cima do histórico — não time grande com release trains.

- **Branch por fatia vertical**: `feat/pricing-engine` (SPEC + motor + golden cases), `feat/settlement-persistence` (schema, adapters JDBC, liquidação atômica, concorrência, Docker), `feat/rest-api-statement` (API, OpenAPI, extrato, `REVIEW.md`), `feat/fx-resilience-ci` (resiliência do câmbio, CI, linter), `docs/architecture-decisions` (C4, `DECISIONS.md`, `AI_USAGE.md`, log estruturado).
- **Conventional commits**: `docs:`, `feat(pricing):`, `feat(persistence):`, `feat(settlement):`, `chore(docker):` — o *porquê* cabe na mensagem curta e no corpo quando a decisão importa.
- **Merge `--no-ff` escrito como PR** (ex.: `Merge PR #1: fase 0 (SPEC) e nucleo do motor de precificacao`): preserva o envelope da fatia no grafo sem perder commits atômicos internos.
- **Por que serve aqui**: na defesa dá para abrir um merge e narrar uma decisão (“por que JDBC”, “por que a ordem idempotência → FX → UPDATE versionado”). Trunk-based puro apagaria essa narrativa; Git Flow completo seria cerimônia sem release/hotfix reais neste desafio.

---

### O que ainda não está entregue

Corte deliberado para caber no esforço e na barra sênior do caminho de dinheiro. **Cada corte está justificado em [`DECISIONS.md`](DECISIONS.md)** — com o risco aceito e o gatilho para reverter a decisão:

- **Frontend do painel/grid** (item 4.2 do enunciado) — o contrato que ele consumiria existe, está em OpenAPI e está testado; falta a camada de apresentação.
- **Autenticação e autorização** — ausência declarada, não disfarçada: é pré-requisito de primeiro deploy, não "endurecimento".
- **Integração real de cotação** (PTAX/mesa) — o mock vive atrás da porta `ExternalFxRateSource`; o que importa no desenho é o comportamento sob falha, e esse está implementado e testado.
- **Tracing distribuído, rate limiting e manifests de deploy** — pagam quando existe segundo serviço, identidade de chamador e cluster.
- **Itens do nível staff** (ADRs formais, design de 1M tx/min, post-mortem do Anexo B) — explicitamente substitutivos daquele nível, não requisitos do sênior. Uma exceção: a **proposta de arquitetura orientada a eventos** existe em [`EDA.md`](EDA.md), escrita como ADR — e a decisão registrada lá é **manter as escritas síncronas**, com os gatilhos observáveis que reabririam a discussão.

---

### Referências rápidas

| Artefato | Uso |
|----------|-----|
| [`SPEC.md`](SPEC.md) | Premissas, precisão, critérios de aceite |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | C4 níveis 1-2, fluxo da liquidação e do câmbio fora do ar |
| [`REVIEW.md`](REVIEW.md) | Review do Anexo A |
| [`DECISIONS.md`](DECISIONS.md) | O que foi cortado e por quê, com risco aceito |
| [`EDA.md`](EDA.md) | Mensageria e eventos (SQS, FIFO, Kafka, cache de leitura): opções avaliadas, decisão e gatilhos |
| [`AI_USAGE.md`](AI_USAGE.md) | Como a IA foi usada, onde errou e como o processo pegou |
| `desafio-tecnico-srm-credit-engine-v2 (3).md` | Enunciado |
| `src/main/resources/db/migration/V1__initial_schema.sql` | Invariantes no banco |
| `docker-compose.yml` / `Dockerfile` | Runtime local e imagem |
| `.github/workflows/ci.yml` | Pipeline de lint e testes |
| `config/checkstyle/checkstyle.xml` | Regras do linter ligadas ao build |
