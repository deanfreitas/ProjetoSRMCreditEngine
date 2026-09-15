# REVIEW — `POST /settlements` (Anexo A)

**PR:** liquidação gerada por IA, mergeada na sexta.  
**Veredito:** **REQUEST CHANGES**. Não entra em `main`. O trecho não é “código feio que a gente limpa depois” — é um caminho direto para pagar duas vezes, precificar errado e expor o banco. Abaixo, achados por severidade, com impacto de negócio e a correção **já existente neste repositório**.

---

## BLOCKER

### B1. INSERT + UPDATE fora de transação e `catch` vazio — liquidação “pela metade”

**O que está errado.** O fluxo grava o settlement e, em seguida, marca o recebível como `SETTLED`. Não há transação envolvendo os dois. O `catch` engole qualquer falha e o handler ainda responde `200`. Cenário real: INSERT ok, UPDATE falha (timeout, constraint, rede) → settlement existe, recebível continua `PENDING`.

**Impacto em produção.** O recebível “ainda está aberto”. Operador, job ou retry do cliente liquida de novo. Cedente recebe **duas vezes**. É exatamente o incidente do Anexo B (três cedentes pagos em duplicidade). Pior: o `catch` vazio apaga a evidência — plantão não vê erro, só o extrato estourado.

**Correção neste repo.** `SettlementService.settle` é `@Transactional`: `receivableRepository.settle(...)` e `settlementRepository.save(...)` na **mesma** transação — ou as duas coisas, ou nenhuma. `JdbcSettlementRepository` só faz INSERT/SELECT; se a unicidade do banco rejeitar, `DuplicateKeyException` vira `ConcurrentSettlementException` e a transação desfaz o UPDATE. Não existe `catch` vazio no caminho feliz nem no de erro.

### B2. Sem idempotência, sem unicidade, sem optimistic locking

**O que está errado.** Não há `Idempotency-Key`, não há UNIQUE em `settlements(receivable_id)` nem em chave de requisição, e o UPDATE não condiciona versão/status. Retry de rede, duplo clique e duas instâncias da API liquidam o mesmo título N vezes.

**Impacto em produção.** Pagamento duplicado por design. Em FIDC isso vira estorno em massa, contestação de cedente e perda de caixa do fundo — não “bug de UX”.

**Correção neste repo.**

- Aplicação: `SettleReceivableCommand` exige `idempotencyKey`; `fingerprint()` (SHA-256 de `receivableId|settlementCurrency`) distingue retry legítimo de chave reaproveitada com payload diferente (`IdempotencyConflictException`).
- Ordem em `SettlementService`: **idempotência primeiro** (`findByIdempotencyKey`) → elegibilidade → câmbio → UPDATE+INSERT.
- Banco (`V1__initial_schema.sql`): `uk_settlements_receivable` e `uk_settlements_idempotency_key`. Unicidade em memória não sobrevive a duas JVMs; a do banco é a última linha de defesa.
- Concorrência: `JdbcReceivableRepository.settle` faz `UPDATE ... WHERE id = :id AND version = :version AND status = 'PENDING'`. Zero linhas → `ConcurrentSettlementException`. Domínio reforça em `Receivable.settled()`.

### B3. SQL injection por interpolação de `receivableId` e `currency`

**O que está errado.**  
`WHERE id = ${receivableId}`, `VALUES (${receivableId}, ..., '${currency}')`. Qualquer corpo de request vira SQL. Autenticação, se existir depois, não salva: o vetor é o próprio endpoint de caixa.

**Impacto em produção.** Leitura/alteração de `receivables`, `settlements`, `assignors`; dump de carteira e documentos de cedente; UPDATE em massa de status; DROP se a role permitir. Além de fraude, é vazamento de dados de cedente (LGPD / confiança da mesa).

**Correção neste repo.** Todo SQL em `JdbcReceivableRepository` e `JdbcSettlementRepository` é parametrizado (`:id`, `:receivableId`, …) via `JdbcClient`. Comentário no repositório de recebível deixa explícito: concatenar id em string é “SQL injection com autenticação de graça”. Colunas listadas no SELECT — sem `SELECT *` com mapeamento implícito por posição.

### B4. Unidade das taxas: `BASE_RATE = 1.0` e `spread = 1.5` / `2.5`

**O que está errado.** Comentário diz “taxa base mensal”, mas `1.0` em `(1 + BASE_RATE + spread)^n` é **100% a.m.**, não 1%. Spreads `1.5` e `2.5` são 150% e 250% a.m. Fórmula correta do domínio usa decimais: base `0.01`, spread `0.015` / `0.025`.

**Impacto em produção.** Para duplicata 3 meses, fator ≈ `(1+1+1.5)^3 = 3.5^3 = 42,875`. Face R$ 100.000 vira VP ≈ R$ 2.332 — o fundo paga **~2,5% do valor correto** (golden C1: R$ 92.859,94). Cedente é lesado de imediato; se alguém “corrigir” só o spread e deixar base em 1.0, o erro muda de magnitude mas continua catastrófico. Precificação por fração do justo é perda de negócio e risco jurídico, não detalhe de arredondamento.

**Correção neste repo.** `PricingProperties` / `FixedBaseRateProvider`: default `0.01`. `DuplicataMercantilStrategy.DEFAULT_MONTHLY_SPREAD = 0.015`, `ChequePreDatadoStrategy = 0.025`. `CompoundDiscountStrategy` compõe `base + spread` em `BigDecimal` e aplica o desconto composto. Comentários e `BaseRateProvider` documentam “0.01 = 1% a.m.”. Golden cases (`GoldenCasesTest`) travam o valor ao centavo.

---

## CRITICAL

### C1. Dinheiro em `number` (IEEE-754) + `toFixed(2)` como política de arredondamento

**O que está errado.** Face, VP e valor pago trafegam em `number`. `toFixed(2)` arredonda em string com half-up enviesado e, pior, **no meio do fluxo** (antes de persistir e na resposta), não como fechamento único do cálculo. Binário não representa 0,01; o erro aparece como centavo perdido/ganho em volume.

**Impacto em produção.** Divergência com o extrato contábil, quebra de `face = VP + deságio`, litígio de centavos que em milhares de liquidações vira diferença material. HALF_UP em massa enviesa a favor de uma ponta (fundo ou cedente).

**Correção neste repo.** `Money` só aceita `BigDecimal` / `String` — **sem** construtor a partir de `double`. `RoundingPolicy`: cálculo em `DECIMAL128`, arredondamento monetário **HALF_EVEN, 2 casas, uma vez no final** (`Money.rounded()`). Banco: `NUMERIC(19,2)` / `NUMERIC(19,6)` em `V1__initial_schema.sql`. Ordem em `PricingEngine.price`: VP pleno → arredonda → deságio fecha a conta → FX sobre VP já arredondado → arredonda moeda destino.

### C2. Câmbio: direção opaca, “latest” sem vigência/freshness, taxa fora da auditoria

**O que está errado.** `getLatestRate("USD")` não diz se a cotação é BRL/USD ou USD/BRL. `presentValue / rate` assume uma direção. Não há `effectiveAt`, teto de staleness nem gravação da taxa usada. Cotação defasada, futura ou invertida entra em silêncio.

**Impacto em produção.**

- Direção errada: com rate 5,4321, dividir ou multiplicar muda o pagamento de ~US$ 17k para dezenas/centenas de milhares — **caixa do fundo ou do cedente evaporam numa única liquidação cross-currency**.
- Taxa velha: paga com PTAX de três dias atrás em dia de volatilidade.
- Sem taxa no registro: liquidação **irreproduzível** — contestação de cedente sem defesa.

**Correção neste repo.** `FxRate` carrega `baseCurrency`, `quoteCurrency`, `rate`, `effectiveAt`, `source`; `convert()` escolhe dividir ou multiplicar pela direção explícita do par. `StoredFxRateProvider.rateFor` pede cotação vigente em `at` e rejeita defasagem acima de `maxStaleness` com `FxRateUnavailableException` (503, nunca fallback). `SettlementService` resolve a cotação **uma vez**, passa em `PricingInput.crossCurrency` e `Settlement` / `JdbcSettlementRepository` **copiam** par, valor, vigência e fonte para a linha de auditoria. CHECK `ck_settlements_fx_required_when_cross_currency` no schema impede cross-currency sem cotação.

### C3. `res.status(200)` sempre — inclusive em falha

**O que está errado.** Qualquer caminho (recebível inexistente, SQL quebrado, catch engolido) devolve 200 + `{ ok: true, amount: ... }`. Cliente, orquestrador e monitor tratam como sucesso.

**Impacto em produção.** Retry deixa de ser seguro (já não há idempotência); dashboards mentem; integração marca “pago” quando não pagou ou quando pagou valor lixo. Anti-padrão eliminatório do próprio enunciado.

**Correção neste repo (premissa SPEC §5 Usabilidade).** Erro de negócio **nunca** é 200: 400 entrada inválida, 404 recebível inexistente (`ReceivableNotFoundException`), 409 conflito/estado (`ConcurrentSettlementException`, `IdempotencyConflictException`, `ReceivableNotSettleableException`), 422 regra de precificação, 503 câmbio indisponível. Replay legítimo devolve a liquidação original (mesmo corpo, 200) **sem** novo pagamento — isso é idempotência, não mascarar erro.

### C4. Fallback silencioso de tipo de recebível

**O que está errado.** `receivable.type === "DUPLICATA" ? 1.5 : 2.5`. Qualquer typo, tipo novo ou valor nulo cai no spread de cheque. Não falha; precifica “parecido”.

**Impacto em produção.** Título de risco A precificado como risco B (ou o inverso). Margem errada por anos até alguém cruzar carteira com planilha. Tipo desconhecido **deveria barrar** a operação, não chutar spread.

**Correção neste repo.** `ReceivableType` é enum fechado (`DUPLICATA_MERCANTIL`, `CHEQUE_PRE_DATADO`). `PricingStrategyRegistry.strategyFor` lança `UnsupportedReceivableTypeException` se não houver strategy — falha alto. Schema: `ck_receivables_type` restringe os valores no banco. Novo tipo = nova strategy registrada, não `else` mágico.

---

## MAJOR

### M1. Sem checagem de estado do recebível

**O que está errado.** Não valida se o título está `PENDING`. Liquidar de novo um `SETTLED` ou `CANCELLED` depende só de o UPDATE “funcionar” (e ele nem falha de forma visível).

**Impacto.** Segunda liquidação de título já pago; ou “ressuscitar” cancelado na carteira.

**Correção.** `Receivable.settled()` só aceita `PENDING` (`ReceivableNotSettleableException`). UPDATE condicionado a `status = 'PENDING'` + versão. UNIQUE por `receivable_id` no settlement.

### M2. Sem validação de input

**O que está errado.** `receivableId` e `currency` saem crus do body. Sem UUID, sem enum de moeda, sem rejeitar body vazio.

**Impacto.** 500 opaco, injection (B3), currency lixo gravada na settlement, comportamento indefinido no FX branch (`currency === "USD"` só).

**Correção.** `SettleReceivableCommand` com `UUID`, `Currency` tipada e `idempotencyKey` não-blank. Moeda fora do conjunto suportado nem chega ao motor.

### M3. `SELECT *` e mapeamento implícito

**O que está errado.** Depende da ordem/presença de colunas. Migração que adiciona coluna no meio quebra VP/face sem compile error.

**Impacto.** Precificação com campo trocado (face vs outra numérica) — pagamento sistematicamente errado até alguém notar no lote.

**Correção.** `JdbcReceivableRepository` projeta colunas nomeadas e monta `Receivable`/`Money` explicitamente. Idem settlements.

### M4. Settlement sem rastro de auditoria financeira

**O que está errado.** INSERT só `receivable_id, amount, currency`. Sem taxa base, spread, prazo, VP, deságio, FX, fingerprint, assignor, timestamps úteis.

**Impacto.** Impossível reproduzir o cálculo na contestação; impossível reconciliar mesa × contabilidade; estorno vira achismo.

**Correção.** Tabela `settlements` em `V1__initial_schema.sql` e `JdbcSettlementRepository.INSERT` persistem o pacote completo. Trigger `trg_settlements_immutable` rejeita UPDATE/DELETE — correção é estorno, não reescrever história.

### M5. Ausência de log, métrica e autorização

**O que está errado.** Zero log de negócio, zero métrica, zero indício de authz (qualquer caller liquida qualquer id se souber/ chutar o id).

**Impacto.** Incidente sem linha do tempo; não se distingue created vs replayed vs conflict; exposição de cedente se o id vazar (IDOR). Plantão do Anexo B voaria cego.

**Correção neste repo.** `SettlementService` loga settlementId, receivableId, assignorId, valores e FX (sem dado sensível demais de cedente no sentido de documento). Contadores `credit_engine.settlements` por resultado e timer `credit_engine.pricing.duration`. Authz de API fica como evolução de borda, mas o desenho já carrega `assignor_id` na settlement para trilha e filtro de extrato.

---

## MINOR

### m1. Prazo (`term`) opaco

O código usa `receivable.term` sem definir se veio da base, se é mês calendário ou 30/360, se face vencida é prazo 0 ou recusa. Neste repo o prazo é derivado em `TermCalculator` a partir de `dueDate` e data de referência (premissa SPEC §2.1), gravado em `term_months` na auditoria.

### m2. Constantes de precificação no controller

Taxa base e spread hardcoded no handler HTTP misturam política de mesa com transporte. Aqui: `BaseRateProvider` + strategies + `PricingProperties` (config, sem redeploy para retune).

### m3. Resposta `{ ok: true, amount: string }`

Não devolve id da liquidação, taxas aplicadas nem distingue created vs replay. Cliente não consegue reconciliar. O outcome em `SettlementOutcome` (created/replayed) + registro completo resolve o contrato.

---

## Priorização por impacto de negócio (o que barra o merge)

| Prioridade | Itens | Por quê |
|---|---|---|
| **Barra merge agora** | B1, B2, B3, B4, C1, C2, C3 | Pagamento duplicado, SQL injection, precificação por fração do justo, centavo/FX errados e 200 mentiroso. Qualquer um sozinho justifica rollback se já tivesse ido. |
| **Barra se o PR insistir em “só o endpoint”** | C4, M1, M2, M4 | Tipo chutado, estado ignorado e auditoria oca tornam o sistema inoperável sob contestação — mesmo com ACID cosmético. |
| **Follow-up aceitável pós-merge do redesenho** | M3 (se SELECT já for parametrizado e tipado), m1–m3, authz fina por cedente, circuit breaker externo de FX além do 503 | Não abrem caminho imediato a pagar duas vezes *se* B1/B2 e schema já estiverem firmes. Ainda entram no mesmo ciclo se o redesenho for o deste repositório. |

**Não negociável para aprovar:** transação única UPDATE condicional + INSERT; unicidade no banco; idempotência com fingerprint; `BigDecimal`/NUMERIC; taxas em decimal; FX tipado e congelado; HTTP semântico; falha alta em tipo desconhecido. O restante pode ser PR de endurecimento — **este** PR não.

---

## Concorrência: a janela de corrida entre as duas queries

Trecho problemático (ordem do Anexo A):

1. `INSERT INTO settlements ...`
2. `UPDATE receivables SET status = 'SETTLED' ...`

**Janela A — dois requests no mesmo recebível ainda `PENDING`.**  
Ambos passam do SELECT inicial (se houver) vendo `PENDING`. Ambos INSERT (sem UNIQUE). Ambos UPDATE. Resultado: **duas settlements**, um status `SETTLED`. Caixa: dois pagamentos.

**Janela B — half-commit.**  
Request 1 INSERT ok, UPDATE falha / processo morre / `catch` engole. Recebível segue `PENDING`. Request 2 (retry ou outro operador) INSERT de novo. Mesmo efeito de A, com a agravação de que o primeiro erro foi silenciado.

**Janela C — “status como trava” sem versão.**  
Mesmo com UPDATE `WHERE status = 'PENDING'`, dois INSERTs antes de qualquer UPDATE ainda duplicam settlement se não houver UNIQUE. Status sozinho **não** é invariante de pagamento; a invariante é “no máximo uma linha em `settlements` por recebível”, com a transição de estado **na mesma transação**.

**Como este repo fecha a janela.**

1. Transação única em `SettlementService`.
2. `UPDATE ... version = :version AND status = 'PENDING'` — o segundo commit vê `affected = 0`.
3. `uk_settlements_receivable` / `uk_settlements_idempotency_key` — se duas transações passarem do UPDATE por bug futuro, o segundo INSERT morre e reverte tudo.
4. Idempotência antes de precificar: retry com a mesma chave **não reabre** a corrida; devolve a settlement original.

Optimistic locking é suficiente porque o conflito real é raro (duplo clique / retry); a unicidade no banco é a rede que não depende do código da aplicação estar perfeito (SPEC §2.8).

---

## Prevenção sistêmica (para essa classe de erro não voltar)

Sem burocracia de checklist genérico — mudanças que teriam **pego este PR na CI ou no review automatizado**:

1. **Teste de concorrência com banco real**  
   Dois `settle` paralelos no mesmo `receivableId` (ver integração em `SettlementServiceIntegrationTest`): exatamente um `created`, o outro `409` / conflito; **uma** linha em `settlements`. Mock de repositório em memória **não** valida UNIQUE nem isolation.

2. **Invariantes no banco, não só no service**  
   UNIQUE por recebível e por idempotency key; CHECK de FX em cross-currency; `NUMERIC` em vez de float; trigger que rejeita UPDATE/DELETE em settlements. Review de migration é review de regra de negócio.

3. **Tipo de dinheiro que não aceita double**  
   `Money` sem construtor `double`; API JSON em string decimal (SPEC §4). ArchUnit / erro de compilação > “lembrete no README”.

4. **Golden cases na CI ao centavo**  
   C1/C2/C3. O PR do Anexo A quebraria C1 por ordens de magnitude (B4) antes de qualquer reviewer humano. Taxa em unidade errada é o tipo de bug que teste de propriedade + golden pega e diff de PR “parece ok” não pega.

5. **Exception handler global e ban de `catch` vazio**  
   Handler mapeia domínio → HTTP semântico (nada de 200 com `ok: true` em falha). Lint/SpotBugs/ESLint `no-empty` + regra de review: `catch` sem log+rethrow/sem mapear status é bloqueio de CI. O comentário `// se falhar aqui, o insert já rodou, então segue o jogo` teria falhado o gate na hora.

6. **Contrato de idempotência no smoke**  
   Mesma `Idempotency-Key` + mesmo body → mesmo `settlementId`, um único pagamento. Mesma chave + body diferente → 409. Isso documenta o protocolo para o cliente e impede regressão do “só dar INSERT de novo”.

7. **FX: teste de direção e de staleness**  
   Par USD/BRL 5,4321 deve bater C3; cotação além de `maxStaleness` → 503, nunca liquidação. Impede o “getLatest + divide” de voltar disfarçado.

---

## Fluxo corrigido (esboço alinhado a este repositório)

```typescript
// Pseudocódigo / TypeScript — espelha SettlementService + schema V1

async function settle(req, res) {
  const cmd = parseCommand(req); // UUID, Currency, Idempotency-Key obrigatória
  const fingerprint = sha256(`${cmd.receivableId}|${cmd.settlementCurrency}`);

  // 1) Idempotência primeiro
  const previous = await settlements.findByIdempotencyKey(cmd.idempotencyKey);
  if (previous) {
    if (previous.requestFingerprint !== fingerprint) {
      return res.status(409).json({ code: "IDEMPOTENCY_CONFLICT" });
    }
    return res.status(200).json({ outcome: "REPLAYED", settlement: previous });
  }

  await db.tx(async (tx) => {
    const receivable = await tx.receivables.findById(cmd.receivableId); // SQL parametrizado
    if (!receivable) return res.status(404).json({ code: "RECEIVABLE_NOT_FOUND" });
    if (receivable.status !== "PENDING") {
      return res.status(409).json({ code: "NOT_SETTLEABLE", status: receivable.status });
    }

    // 2) Câmbio resolvido UMA vez e congelado (ou 503)
    let fx = null;
    if (cmd.settlementCurrency !== receivable.faceCurrency) {
      fx = await fxProvider.rateFor(cmd.settlementCurrency, receivable.faceCurrency, now);
      // fx: { base, quote, rate, effectiveAt, source } — convert() sabe a direção
    }

    // 3) Precificação decimal (base 0.01, spread 0.015|0.025), BigDecimal, half-even 1x no final
    const pricing = pricingEngine.price({ receivable, fx, termMonths, at: now });

    // 4) UPDATE condicionado a versão + status  e  INSERT na MESMA transação
    const updated = await tx.receivables.settle({
      id: receivable.id,
      version: receivable.version, // WHERE version = ? AND status = 'PENDING'
    });
    if (updated === 0) throw new ConcurrentSettlementError(receivable.id);

    try {
      await tx.settlements.insert({
        id: randomUUID(),
        receivableId: receivable.id,
        assignorId: receivable.assignorId,
        idempotencyKey: cmd.idempotencyKey,
        requestFingerprint: fingerprint,
        ...pricing,           // face, VP, deságio, taxas, settlementAmount
        fx,                   // copiada, não “a taxa atual”
        settledAt: now,
      });
    } catch (e) {
      if (isUniqueViolation(e)) throw new ConcurrentSettlementError(receivable.id);
      throw e; // nunca engolir — rollback da tx
    }

    return res.status(201).json({ outcome: "CREATED", settlement: /* registro completo */ });
  });
}
```

Pontos que o Anexo A não tinha e este esboço torna não-opcionais: **idempotência → elegibilidade → FX congelado → UPDATE versionado + INSERT atômicos → HTTP semântico → erro propaga e reverte**.

---

## Encerramento

Não peço “refatorar nomes” nem “adicionar testes” como mantra. Peço que o caminho de dinheiro respeite invariantes que o banco e o domínio **já** expressam neste repositório. O Anexo A falha em segurança (injection), em atomicidade (pagamento duplicado), em semântica financeira (unidade de taxa, double, FX) e em operabilidade (200 mentiroso, catch vazio, zero auditoria). Com o desenho atual (`SettlementService`, `Money`/`RoundingPolicy`, `FxRate`/`StoredFxRateProvider`, strategies + registry, `V1__initial_schema.sql`), esses defeitos não são teóricos — foram eliminados de propósito. Este PR, do jeito que está, é rollback, não polish.
