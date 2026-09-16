# SPEC — SRM Credit Engine

> Fase 0 do desafio. Documento de **premissas assumidas**, não de requisitos recebidos.
> Cada premissa abaixo é uma decisão que eu tomei e que defendo — e que um analista de
> negócio poderia derrubar em 5 minutos de conversa. Onde a decisão é reversível, indico o
> ponto do código que muda.

## 1. Escopo desta entrega

Backend apenas (decisão registrada em `DECISIONS.md`): API de precificação e liquidação de
recebíveis com caixa multimoedas BRL/USD. Nível-alvo: **sênior** — corretude do motor,
ACID + idempotência, concorrência, observabilidade e resiliência.

## 2. Ambiguidades do enunciado e premissas adotadas

### 2.1 Unidade do prazo na fórmula

**Premissa:** o prazo é expresso em **meses inteiros** e os juros são compostos mensais,
como nos golden cases. Quando a operação informa data de vencimento em vez de prazo, a
conversão usa **mês comercial de 30 dias (convenção 30/360)** e **arredonda a fração de mês
para cima**.

**Por quê:** o fundo fica exposto ao risco durante todo o mês iniciado; arredondar para
baixo compraria o título mais caro do que o risco assumido. Truncar para baixo é a premissa
alternativa razoável — é decisão comercial, não técnica.

**Onde muda:** `TermCalculator`. Não há outro lugar no código que interprete prazo.

### 2.2 Origem e valor da taxa base

**Premissa:** a taxa base é **parâmetro operacional do fundo com vigência**, não constante
de código nem taxa buscada em tempo real de um índice externo. Nesta entrega ela vem de
configuração (`credit-engine.pricing.base-monthly-rate`, default `0.01` = 1,00% a.m.,
igual aos golden cases), atrás da interface `BaseRateProvider`, que já recebe a data de
referência da operação.

**Por quê:** taxa base é decisão de mesa (custo de funding + política do fundo). Precisa
mudar sem deploy e precisa ser auditável por data. A interface recebendo data permite
trocar por tabela histórica ou CDI importado sem tocar no motor.

**Consequência de auditoria:** a taxa base e o spread **efetivamente aplicados** são
gravados em cada precificação/liquidação (`PricingResult`). Sem isso, mudar o parâmetro
tornaria impossível reproduzir uma liquidação antiga — e contestação de cedente é cenário
real.

### 2.3 Composição de taxa base e spread

**Premissa:** taxa de desconto efetiva = **soma aritmética** `base + spread`
(1,00% + 1,50% = 2,50% a.m.), aplicada de forma composta no prazo. É o que os golden cases
exigem.

**Alternativa não adotada:** composição geométrica `(1+base)·(1+spread)−1`, financeiramente
mais defensável para empilhar taxas. Ficaria em `CompoundDiscountStrategy.effectiveMonthlyRate`
— um método, um teste.

### 2.4 Política e momento do arredondamento

**Premissa:**

1. Todo o encadeamento intermediário roda em `MathContext.DECIMAL128` (34 dígitos), **sem**
   arredondamento monetário.
2. O arredondamento monetário é **HALF_EVEN com 2 casas** e acontece **uma única vez**, no
   resultado final de cada moeda.
3. O deságio é calculado como `face − valor presente já arredondado`, para que
   `face = valor presente + deságio` feche ao centavo (invariante coberta por teste).
4. Em cross-currency, converte-se o valor presente **já arredondado em BRL** e arredonda-se
   o resultado em USD — exatamente a premissa da tabela de aferição.

**Por quê HALF_EVEN:** HALF_UP enviesa sistematicamente a favor de uma ponta; em milhares de
liquidações isso vira diferença contábil. HALF_EVEN é o padrão bancário.

**Onde muda:** `RoundingPolicy` (ponto único) e a ordem em `PricingEngine.price`.

### 2.5 Qual taxa de câmbio vale na liquidação

**Premissa:** vale a **cotação vigente mais recente na data/hora da liquidação**, resolvida
**uma vez** no início da transação e **congelada** no registro de auditoria (par, valor,
`effectiveAt`, fonte). Não se usa "a taxa atual" no momento da leitura do relatório, nem a
taxa da data de vencimento.

**Por quê:** é o que reflete o caixa: o fundo paga o cedente hoje, ao câmbio de hoje. A taxa
da data de vencimento seria projeção (exigiria hedge/forward, que não está no escopo). Ler
"a mais recente" no momento do relatório reescreveria o passado.

**Regra de consistência:** simulação e liquidação **não compartilham** cotação. Se a cotação
mudou entre a simulação exibida ao operador e a confirmação, a liquidação usa a cotação nova
e **retorna a taxa aplicada na resposta** — quem decide se aceita a variação é a mesa, não o
sistema. (Um `expectedFxRate` opcional com rejeição por divergência é a evolução natural;
está listado em `DECISIONS.md`.)

**Se o provedor de cotação cair:** a liquidação cross-currency **falha explicitamente**
(HTTP 503, `FX_RATE_UNAVAILABLE`), sem fallback para taxa 1:1, taxa antiga
arbitrária ou valor default. Liquidar com taxa errada é pior do que não liquidar. Timeout +
retry com circuit breaker protegem o pico; o estado do recebível só muda dentro da transação
que obteve a taxa.

### 2.6 Idempotência da liquidação

**Premissa:** o cliente envia `Idempotency-Key` (header) por tentativa de liquidação. A
chave é persistida com restrição de unicidade junto da liquidação, na **mesma transação**.
Repetição da mesma chave devolve **a liquidação original** (mesmo corpo, `200 OK`), sem
recalcular nem repagar. Chave igual com payload diferente é conflito (`409`).

**Por quê:** retry de rede e duplo clique são certeza, não hipótese. A unicidade tem de
estar no banco: validação em memória não sobrevive a duas instâncias da aplicação.

**Acréscimo posterior, sem mudança de premissa:** há um guarda de idempotência em Redis
**na frente** dessa regra, que reserva a chave antes de resolver câmbio e precificar, para
que a repetição não custe o trabalho caro. Ele é otimização, não autoridade — falha aberto
para o caminho acima, e a unicidade continua no banco, na mesma transação do pagamento.
Ver `DECISIONS.md`, seção 5.

### 2.7 Imutabilidade e auditoria

**Premissa:** liquidação registrada não é editável nem deletável pela aplicação — não existe
`PUT /settlements/{id}` nem `DELETE`. Correção se faz por **evento compensatório**
(estorno), preservando o registro original. O registro guarda valor de face, valor presente,
valor pago, moeda, taxa base, spread, cotação aplicada e timestamps.

### 2.8 Concorrência

**Premissa:** um recebível só pode ser liquidado uma vez. A garantia é **optimistic locking**
(coluna de versão) sobre o recebível, mais restrição de unicidade de liquidação por
recebível no banco. Em conflito, a segunda transação falha com `409 Conflict` — e existe
teste com duas liquidações simultâneas demonstrando o conflito sendo tratado.

**Por quê optimistic e não pessimistic:** conflito real é raro (duplo clique no mesmo
título), então pagar lock pessimista em todo o fluxo é caro; e a restrição de unicidade é a
rede de segurança que não depende do código da aplicação estar correto.

### 2.9 Precisão no banco de dados

**Premissa:** PostgreSQL com `NUMERIC(19,2)` para valores monetários e `NUMERIC(19,6)` para
taxas de câmbio e juros. Nunca `float`/`double`/`real`. Na aplicação, sempre `BigDecimal`;
não existe construtor de `Money` a partir de `double`.

**Por quê 19,2:** 17 dígitos inteiros cobrem qualquer lote de FIDC com folga; 2 casas é a
unidade contábil de BRL e USD. Taxas com 6 casas cobrem BRL/USD (4 casas de mercado) com
margem.

### 2.10 Modelo de dados

**Premissa:** quatro tabelas — `assignors`, `receivables`, `fx_rates` e `settlements` —
normalizadas até 3FN, com uma desnormalização deliberada: `settlements` **copia** os valores,
as taxas aplicadas e a cotação em vez de referenciá-las, porque registro de auditoria
precisa continuar reproduzível depois de a tabela de cotações e a taxa base mudarem.

O **diagrama ER**, com chaves, `UNIQUE`, `CHECK`, índices e o gatilho de imutabilidade, está
em `ARCHITECTURE.md`, seção *Modelo de dados*; a fonte de verdade é
`src/main/resources/db/migration/V1__initial_schema.sql`.

## 3. Perguntas que eu faria ao negócio

1. **Prazo:** contagem em dias corridos convertidos em mês comercial (30/360), dias úteis, ou
   meses de calendário? Fração de mês arredonda para cima, para baixo, ou é proibida?
2. **Taxa base:** é definida manualmente pela mesa, indexada (CDI/Selic), ou varia por
   cedente/fundo? Com que frequência muda e quem aprova a mudança?
3. **Composição:** base e spread somam ou compõem geometricamente?
4. **Câmbio:** existe *spread* cambial (compra/venda) além da cotação? Qual a tolerância de
   variação entre a simulação vista pelo operador e a liquidação — e quem assume a diferença?
5. **Liquidação parcial:** um lote pode ser liquidado parcialmente, ou é tudo-ou-nada por
   recebível?
6. **Estorno:** quem pode estornar, em que janela, e o estorno reverte ao câmbio original ou
   ao câmbio do dia do estorno?
7. **Recebível vencido:** pode ser liquidado (prazo 0) ou o sistema deve recusar?
8. **Cedente:** qual o documento de identidade canônico (CNPJ/CPF) e ele pode mudar de
   moeda de pagamento entre operações?
9. **Retenção:** por quanto tempo o registro de auditoria precisa ser mantido e precisa ser
   exportável para o administrador do fundo?

## 4. Decisões de precisão numérica (resumo)

| Onde | Tipo | Escala | Arredondamento |
|---|---|---|---|
| Aplicação — valores | `BigDecimal` dentro de `Money` | 2 no resultado, plena no cálculo | HALF_EVEN, uma vez, no final |
| Aplicação — cálculo intermediário | `BigDecimal` + `MathContext.DECIMAL128` | 34 dígitos significativos | nenhum |
| Aplicação — taxas | `BigDecimal` | 6 | HALF_EVEN |
| Banco — valores | `NUMERIC(19,2)` | 2 | — |
| Banco — taxas | `NUMERIC(19,6)` | 6 | — |
| API (JSON) | string decimal, nunca número de ponto flutuante | 2 | — |

Aferição: os três golden cases são testados ao centavo (`GoldenCasesTest`), com valores
esperados conferidos por cálculo independente, e não pelo próprio motor.

## 5. Critérios de aceite definidos por mim

### Corretude
- Os três golden cases batem ao centavo; invariante `face = valor presente + deságio` vale
  para toda precificação.
- Nenhum valor monetário trafega por `float`/`double` em qualquer camada.
- Alterar a taxa base por configuração não quebra a aferição (o teste fixa suas próprias
  premissas).

### Usabilidade (API)
- Simulação de precificação responde sem efeito colateral e devolve o rastro do cálculo
  (taxas e prazo aplicados), para o operador entender de onde saiu o número.
- Erro de negócio nunca retorna `200`: `400` para entrada inválida, `404` para recebível
  inexistente, `409` para conflito/estado inválido, `422` para regra de negócio violada,
  `503` para indisponibilidade de câmbio. Corpo padronizado com código estável de erro.
- Todo endpoint documentado em OpenAPI, com exemplos dos golden cases.

### Segurança e integridade
- Zero concatenação de string em SQL: consultas parametrizadas, sempre (o Anexo A erra
  exatamente aqui).
- Liquidação é atômica: ou registra e muda o estado do recebível, ou nada acontece.
- Nenhuma exceção engolida: erro inesperado é logado com contexto e vira `5xx`.
- Nenhum endpoint altera ou remove liquidação registrada.

### Desempenho (ambiente local, banco em Docker)
- Simulação (sem persistência): p95 < 50 ms.
- Liquidação (com transação e câmbio mockado): p95 < 300 ms.
- Extrato paginado sobre ~100 mil liquidações: p95 < 500 ms com filtro por período e
  cedente, usando SQL nativo e índices — não ORM puro.
- Duas liquidações simultâneas do mesmo recebível: exatamente uma vence; a outra recebe
  `409` e **não** gera pagamento.

### Operação
- Logs estruturados (JSON) com correlação por requisição e chave de idempotência; valores
  monetários no log, nunca dado sensível de cedente. *(Entregue: `logback-spring.xml` +
  `CorrelationIdFilter`.)*
- Métricas de negócio expostas: contador de liquidações por resultado e latência do motor de
  precificação.
- Integração de câmbio com timeout e circuit breaker; queda do provedor degrada apenas
  liquidação cross-currency, não a operação em BRL.
