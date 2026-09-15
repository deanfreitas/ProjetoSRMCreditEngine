# SRM Credit Engine

Backend de precificação e liquidação de recebíveis com caixa multimoedas (BRL/USD). Recebe um lote de títulos (duplicata mercantil, cheque pré-datado), calcula o valor presente com deságio por tipo de ativo, converte câmbio quando o cedente recebe em moeda distinta da face e grava a liquidação de forma atômica, idempotente e auditável.

**Entrega somente backend** — decisão consciente de escopo (nível sênior do desafio). O enunciado prevê painel/grid no frontend; aqui a prioridade foi o caminho de dinheiro: motor ao centavo, ACID, concorrência, contrato HTTP e evidência de teste. A API OpenAPI é a interface do operador nesta entrega. Premissas de negócio e precisão estão em [`SPEC.md`](SPEC.md) — este README remete a elas, não as repete.

---

### Como rodar

Requisitos: **JDK 21**, **Maven 3.9+**, **Docker** (Compose e Testcontainers).

#### (a) Tudo via Docker Compose

Sobe PostgreSQL 16 (healthcheck com `pg_isready`) e a aplicação (imagem multi-stage Java 21). A app só sobe depois do banco saudável.

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

#### (b) App local + Postgres do Compose

```bash
docker compose up -d db
```

Com o banco em `localhost:5432`, os defaults de `application.yml` bastam (`DB_URL=jdbc:postgresql://localhost:5432/srm_credit_engine`, usuário/senha `srm`). Flyway aplica `V1__initial_schema.sql` na subida.

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

A suíte tem **70 testes** (invocações JUnit, incluindo `@ParameterizedTest`). Testes de integração usam **Testcontainers** (`PostgreSQL`) — **Docker precisa estar rodando**. H2 não entra no caminho: dialeto, `NUMERIC` e locking importam exatamente aqui.

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
# 200: content com a liquidação, totals do filtro inteiro (não só da página). Prova SQL nativo + paginação server-side.
```

Documentação interativa: Swagger UI e `/v3/api-docs` (springdoc).

---

### Escolha de stack

| Escolha | Por quê |
|---------|---------|
| **Java 21 + Spring Boot 3.3** | Tipagem forte, ecossistema maduro para API financeira, `BigDecimal` nativo, validação, Actuator e transações sem reinventar infraestrutura. LTS e tooling previsível na defesa ao vivo. |
| **Spring JDBC (`JdbcClient`), não JPA** | SQL e optimistic locking explícitos (`UPDATE ... WHERE version = ?`). Extrato nasce em SQL nativo com índices — o enunciado (4.1.6/4.1.7) pede isso. Custo: mapeamento manual de rows; aceito de propósito. |
| **PostgreSQL 16** | `NUMERIC(19,2)` / `NUMERIC(19,6)` exatos, constraints, índices e trigger de imutabilidade. Float/real não entram. |
| **Flyway** | Schema versionado junto do código; a primeira migração já carrega as invariantes de negócio. |
| **Testcontainers** | Mesmo dialeto e locking da produção. H2 esconderia exatamente as falhas que o desafio pune. |
| **springdoc-openapi** | Contrato HTTP gerado do código — não envelhece separado da implementação. |

Detalhes de premissa (prazo 30/360, taxa base configurável, HALF_EVEN, etc.) → [`SPEC.md`](SPEC.md).

---

### Arquitetura em camadas

Pacote base: `br.com.srm.creditengine`.

| Pacote | Responsabilidade |
|--------|------------------|
| `api` | Controllers REST, `ApiExceptionHandler` (RFC 9457 / `problem+json`), DTOs de fronteira (`dto`). |
| `application` | Casos de uso: `assignor`, `receivable`, `pricing`, `settlement`, `statement`, `fx`. Orquestra domínio + portas. |
| `domain` | Núcleo sem anotação de framework: `money`, `pricing` (Strategy + motor), `fx`, `receivable`, `settlement`, `assignor`. Regras, value objects, exceções de domínio. |
| `infrastructure` | Adaptadores: `persistence` (JDBC) e `fx` (`StoredFxRateProvider`). |
| `config` | Beans de pricing/FX, OpenAPI, propriedades (`credit-engine.*`). |

O **domínio não depende de Spring/JPA**. O extrato (`SettlementStatementController` → `SettlementStatementQuery` / `JdbcSettlementStatementQuery`) **atalha da API direto para a porta de leitura em SQL nativo** — autorizado no item **4.1.7** do enunciado; inventar um service que só repassa a chamada seria camada vazia.

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

### Idempotência e concorrência

**Contrato HTTP** (`POST /api/v1/settlements`):

| Situação | Status | Corpo |
|----------|--------|--------|
| Primeira execução | **201** | liquidação nova, `replayed: false`, header `Location` |
| Mesma `Idempotency-Key` + mesmo payload | **200** | liquidação original, `replayed: true` |
| Mesma chave + payload diferente | **409** | `IDEMPOTENCY_KEY_CONFLICT` |
| Header ausente | **400** | `MISSING_REQUIRED_HEADER` |

`Idempotency-Key` é **obrigatório** (header, máx. 120 chars). A chave é da requisição (retry de rede), não do body.

**Três camadas de defesa** (nesta ordem no `SettlementService`):

1. **Regra de domínio / aplicação** — lookup pela chave antes de precificar; fingerprint do pedido; recebível só liquida a partir de `PENDING`.
2. **Optimistic locking** — `UPDATE receivables SET status='SETTLED', version=version+1 WHERE id=? AND version=? AND status='PENDING'`; zero linhas → `CONCURRENT_SETTLEMENT` (409).
3. **Unicidade no banco** — `uk_settlements_receivable`, `uk_settlements_idempotency_key`; `DuplicateKeyException` vira conflito e a transação desfaz. Trigger `trg_settlements_immutable` barrando UPDATE/DELETE em `settlements`.

Tudo na **mesma `@Transactional`**: ou marca o recebível e grava a liquidação, ou nada. Teste de corrida: **8 threads**, exatamente **1** pagamento.

---

### Contrato de erros

`ApiExceptionHandler` concentra o mapa domínio → HTTP. **Erro nunca responde 200.** 4xx → log de aviso (sem stack); 5xx → log de erro com causa. Corpo: `application/problem+json` com `errorCode` estável e `timestamp`.

| Status | Situação | Exemplo de `errorCode` |
|--------|----------|-------------------------|
| **400** | Payload/header inválido, JSON ilegível | `VALIDATION_FAILED`, `MISSING_REQUIRED_HEADER`, `MALFORMED_REQUEST`, `INVALID_PARAMETER` |
| **404** | Recurso inexistente | `RECEIVABLE_NOT_FOUND`, `ASSIGNOR_NOT_FOUND`, `SETTLEMENT_NOT_FOUND` |
| **409** | Colisão de estado / corrida / chave reusada | `RECEIVABLE_NOT_SETTLEABLE`, `CONCURRENT_SETTLEMENT`, `IDEMPOTENCY_KEY_CONFLICT`, `ASSIGNOR_ALREADY_REGISTERED`, `FX_RATE_ALREADY_REGISTERED` |
| **422** | Sintaxe ok, sem sentido financeiro | `INVALID_PRICING_INPUT`, `INVALID_ARGUMENT`, `MISSING_FX_RATE`, `FX_RATE_NOT_APPLICABLE` |
| **503** | Dependência indisponível (câmbio defasado/ausente, persistência) | `FX_RATE_UNAVAILABLE`, `PERSISTENCE_UNAVAILABLE` |
| **500** | Invariante interna / bug | `INTERNAL_ERROR`, `CURRENCY_MISMATCH`, `UNSUPPORTED_RECEIVABLE_TYPE` |

Nenhuma exceção é engolida: o `catch` vazio do Anexo A é o anti-padrão que este handler existe para eliminar.

---

### Observabilidade

**Métricas de negócio** (Micrometer):

- `credit_engine.settlements{result}` — contador com `result` ∈ `created` \| `replayed` \| `idempotency_conflict` \| `concurrent_conflict`
- `credit_engine.pricing.duration` — timer do motor (simulação e liquidação)

**Actuator** (`application.yml`): expostos `health`, `info`, `metrics`, `prometheus`.

Logs de liquidação trazem `settlementId`, `receivableId`, `idempotencyKey`, valores e taxa — o bastante para distinguir created vs replay vs conflito no plantão.

---

### Testes / evidência

**70 invocações** no total (`mvn test`). Por classe:

| Classe | # | O que prova |
|--------|---|-------------|
| `GoldenCasesTest` | 4 | C1/C2/C3 ao centavo + invariante face = VP + deságio (cálculo independente do motor) |
| `PricingEngineTest` | 19 | Bordas (prazo 0, 1 centavo, prazo longo), HALF_EVEN (empates), FX obrigatório/direção, strategies |
| `TermCalculatorTest` | 6 | 30/360, fração arredonda para cima, vencido rejeitado |
| `MoneyTest` | 4 | soma exata, moedas distintas falham, arredondamento explícito |
| `SettlementServiceIntegrationTest` | 11 | C1/C3 no banco, replay, chave conflituosa, **8 threads → 1 pagamento**, rollback se FX cai, taxa defasada/futura, **imutabilidade barrada pelo trigger** |
| `SettlementStatementQueryIntegrationTest` | 8 | filtros (período, cedente, moeda), paginação, totais no banco, página abusiva |
| `CreditEngineApiIntegrationTest` | 8 | fluxo HTTP completo C3, 2ª liquidação 409, FX ausente 503, OpenAPI exposto |
| `SettlementControllerTest` | 10 | contrato HTTP de status (201/200/400/404/409/503) com handler real |

Integração = PostgreSQL real via Testcontainers + schema Flyway.

---

### Estratégia de branching e histórico Git

Contexto: um autor, prazo curto, defesa ao vivo em cima do histórico — não time grande com release trains.

- **Branch por fatia vertical**: `feat/pricing-engine` (SPEC + motor + golden cases), `feat/settlement-persistence` (schema JDBC, liquidação, concorrência, API, Docker).
- **Conventional commits**: `docs:`, `feat(pricing):`, `feat(persistence):`, `feat(settlement):`, `chore(docker):` — o *porquê* cabe na mensagem curta e no corpo quando a decisão importa.
- **Merge `--no-ff` escrito como PR** (ex.: `Merge PR #1: fase 0 (SPEC) e nucleo do motor de precificacao`): preserva o envelope da fatia no grafo sem perder commits atômicos internos.
- **Por que serve aqui**: na defesa dá para abrir um merge e narrar uma decisão (“por que JDBC”, “por que a ordem idempotência → FX → UPDATE versionado”). Trunk-based puro apagaria essa narrativa; Git Flow completo seria cerimônia sem release/hotfix reais neste desafio.

---

### O que ainda não está entregue

Corte deliberado para caber no esforço e na barra sênior do caminho de dinheiro. Quando existir, o detalhe de cada corte vai em `DECISIONS.md`.

- **Resiliência do provedor de câmbio mockado**: timeout / retry / circuit breaker na integração externa. Hoje a falha é explícita (503 + rollback); falta o envelope de resiliência do cliente HTTP.
- **CI (GitHub Actions)** rodando `mvn test` e gates básicos.
- **Diagrama C4** (context + container).
- **`DECISIONS.md`** e **`AI_USAGE.md`** (exigidos pelo enunciado §7 e §10) — ainda não versionados.
- **`REVIEW.md` já existe** (code review reverso do Anexo A, amarrado às correções deste repositório).
- Frontend do painel/grid (fora do escopo desta entrega backend).

---

### Referências rápidas

| Artefato | Uso |
|----------|-----|
| [`SPEC.md`](SPEC.md) | Premissas, precisão, critérios de aceite |
| [`REVIEW.md`](REVIEW.md) | Review do Anexo A |
| `desafio-tecnico-srm-credit-engine-v2 (3).md` | Enunciado |
| `src/main/resources/db/migration/V1__initial_schema.sql` | Invariantes no banco |
| `docker-compose.yml` / `Dockerfile` | Runtime local e imagem |
