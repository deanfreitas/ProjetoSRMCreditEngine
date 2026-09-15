# Desafio Técnico: SRM Credit Engine — Plataforma de Cessão de Crédito Multimoedas
### Edição AI-native (v2)

---

## 1. Contexto de negócio

A **SRM Asset** é referência em fundos de investimento, especialmente FIDCs (Fundos de Investimento em Direitos Creditórios). Nossa operação envolve a aquisição de ativos (duplicatas, contratos, recebíveis) de empresas cedentes, provendo liquidez ao mercado.

Com a globalização do portfólio, o fundo passou a operar com caixa multimoedas (BRL e USD). A mesa de operações precisa de um sistema — o **SRM Credit Engine** — para precificar e liquidar esses ativos com segurança e precisão decimal.

**O problema:** receber um lote de recebíveis, calcular o deságio (desconto) com base no risco do ativo e na moeda de pagamento, e registrar a transação de forma auditável.

---

## 2. O que mudou nesta versão — leia antes de tudo

Assumimos que você vai usar IA (Claude, ChatGPT, Gemini, Copilot, agentes). Não é diferencial, é o novo normal. O que este desafio avalia é **se você sabe o que está fazendo**: julgamento de engenharia, domínio do problema financeiro e capacidade de verificar o que a máquina produz.

Consequências práticas:

1. **Uso de IA é livre e esperado** — inclusive na defesa técnica ao vivo (ver seção 9).
2. **Não avaliamos por volume.** Menos código, bem justificado, vale mais que muito código genérico. Volume é barato agora; julgamento não.
3. **Toda entrega passa por uma defesa síncrona obrigatória**, com alteração de código ao vivo no seu próprio repositório. Entrega sem defesa não é avaliada.
4. **Fornecemos casos de aferição (golden cases).** Seu motor de precificação precisa reproduzir os valores ao centavo.
5. **A rubrica de avaliação é pública** (seção 11). Não há pegadinha sobre o que vale ponto — a dificuldade está em fazer bem, não em adivinhar o critério.

---

## 3. Fase 0 — Spec antes de código (`SPEC.md`)

Este enunciado contém **ambiguidades propositais**. Alguns exemplos (há outros): a unidade do prazo na fórmula, a origem e o valor da taxa base, a política de arredondamento, e **qual** taxa de câmbio vale no momento da liquidação (a da data da operação? a mais recente? a da data de vencimento?).

Antes de escrever código, entregue um `SPEC.md` curto (1–2 páginas) contendo:

- **Premissas adotadas** para cada ambiguidade que você identificou.
- **Perguntas que você faria ao negócio** se este fosse um projeto real.
- **Decisões de precisão numérica:** tipo de dado no banco e na aplicação, política de arredondamento, e em que momento do cálculo o arredondamento acontece.
- **Critérios de aceite** que você mesmo definiu para usabilidade, segurança e desempenho.

Não existe resposta única — existe premissa bem defendida. Pode usar IA para explorar as opções; a responsabilidade pelas decisões é sua, e elas serão questionadas na defesa.

---

## 4. Fase 1 — Core (todos os níveis)

Stack livre (backend e frontend), desde que adequada a ambiente financeiro — tipagem forte e frameworks maduros são diferenciais. Justifique a escolha no README.

### 4.1 Backend

1. **Gestão de câmbio (Currency Engine):** armazenar e prover taxas (ex.: USD→BRL), com endpoint de atualização manual ou integração mockada. Cada taxa deve ter data/hora de vigência.
2. **Motor de precificação (Strategy):** cada tipo de recebível tem spread próprio; desacople a regra do cálculo com o padrão Strategy.
   - Fórmula base: `Valor Presente = Valor de Face / (1 + Taxa Base + Spread)^Prazo`
   - Duplicata Mercantil: spread 1,5% a.m. · Cheque Pré-datado: spread 2,5% a.m.
   - Cross-currency (título em BRL, pagamento em USD): conversão cambial ao final.
3. **Persistência e integridade:** banco relacional (preferencial); liquidações respeitam ACID — nenhuma liquidação fica "pela metade". O endpoint de liquidação deve ser **idempotente**: a mesma requisição repetida (retry de rede, duplo clique) não pode gerar duas liquidações.
4. **Auditabilidade:** cada liquidação gera registro imutável com valores, taxa de câmbio efetivamente usada e timestamps. Alterar uma liquidação registrada não é uma operação do sistema.
5. **API RESTful:** verbos e códigos de status semânticos, documentação OpenAPI/Swagger.
6. **Extrato de liquidação:** rota analítica com filtro por período, cedente e moeda. Diferencial (pleno+): query builder ou SQL nativo otimizado em vez de ORM puro para relatórios.
7. **Arquitetura em camadas:** aplicação, negócio e persistência separadas; relatórios podem atalhar para duas camadas.

### 4.2 Frontend

1. **Painel do operador (todos os níveis):** input do recebível (valor, vencimento, tipo, moeda de pagamento) com simulação do valor líquido em tempo real.
2. **Grid de transações (pleno+):** histórico com paginação server-side e filtros dinâmicos. Para júnior, uma listagem simples atende.
3. **Arquitetura:** separação entre apresentação e lógica/estado; estado global só se justificar.

### 4.3 Golden cases — aferição obrigatória

Para permitir verificação objetiva, os casos abaixo usam **premissas fixas** (independentes das suas escolhas no `SPEC.md` — elas existem só para aferição):

- Taxa base: **1,00% a.m.** · Prazo em **meses inteiros**, juros compostos mensais.
- Arredondamento: **half-even (banker's rounding), 2 casas, apenas no resultado final**.
- Cross-currency: converte-se o valor presente em BRL **já arredondado** pela taxa informada.

| # | Tipo | Valor de face | Prazo | Moeda pgto. | Câmbio (BRL/USD) | Valor presente esperado | Deságio |
|---|------|--------------:|------:|-------------|-----------------:|------------------------:|--------:|
| C1 | Duplicata Mercantil | R$ 100.000,00 | 3 meses | BRL | — | **R$ 92.859,94** | R$ 7.140,06 |
| C2 | Cheque Pré-datado | R$ 25.000,00 | 2 meses | BRL | — | **R$ 23.337,77** | R$ 1.662,23 |
| C3 | Duplicata Mercantil | R$ 100.000,00 | 3 meses | USD | 5,4321 | **US$ 17.094,67** | R$ 7.140,06 |

Inclua um teste automatizado que valida os três casos. Se o seu motor não bate esses valores ao centavo, há um problema de precisão numérica — e esse é exatamente o tipo de erro que não pode existir em sistema financeiro.

---

## 5. Fase 2 — Code review reverso (todos os níveis)

O **Anexo A** contém um trecho de código "gerado por IA e aprovado às pressas" no endpoint de liquidação. Escreva um `REVIEW.md` como se você fosse o revisor do PR:

- Liste os problemas **em ordem de severidade**.
- Para cada um: qual o impacto em produção e qual a correção proposta.
- A profundidade esperada cresce com a senioridade — de defeitos evidentes (júnior) a falhas de concorrência, semântica financeira e prevenção sistêmica (sênior/staff).

Este exercício é deliberadamente barato de avaliar e difícil de fingir: qualquer IA lista defeitos, mas priorizar por impacto de negócio e defender as correções ao vivo exige entendimento real.

---

## 6. Fase 3 — Expectativas por senioridade

A senioridade aqui **não é acúmulo de artefatos** — é mudança de eixo: júnior entrega corretude e clareza; pleno entrega robustez; sênior entrega operação; staff/tech lead entrega decisão e prevenção sistêmica.

### 🟢 Júnior — corretude e clareza
- API + frontend rodando localmente, golden cases passando.
- `SPEC.md` com premissas (mesmo que simples) e banco normalizado com diagrama ER básico.
- Testes cobrindo o cálculo de precificação.
- README com "como rodar" que funciona de primeira.
- Git: commits atômicos com mensagens descritivas; branches por funcionalidade (nada direto na `main`).

### 🟡 Pleno — robustez e fluxo *(acumulativo)*
- Docker + Docker Compose orquestrando aplicação e banco.
- Tratamento de erros global (exception handlers) e validação de input robusta.
- **Idempotência na liquidação implementada e testada.**
- Testes unitários das strategies incluindo casos de borda e arredondamento.
- Grid com paginação server-side.
- Git: conventional commits; PRs (mesmo trabalhando sozinho) com descrição do **porquê**, não só do quê.

### 🔴 Sênior — operação *(acumulativo)*
- **Concorrência:** optimistic locking na liquidação, com um teste que demonstra o conflito sendo tratado (duas liquidações simultâneas do mesmo recebível).
- **Observabilidade:** logs estruturados e ao menos 1–2 métricas de negócio (ex.: liquidações/min, latência do motor).
- **Resiliência:** timeout + retry ou circuit breaker na integração (mockada) de câmbio; o que acontece se o provedor de taxa cai no meio de uma liquidação?
- CI (GitHub Actions ou similar) rodando testes e linter.
- Diagrama C4 (níveis 1 e 2).

### 🟣 Staff / Tech Lead — decisão e prevenção *(parcialmente substitutivo: menos código, mais decisão)*
Você pode reduzir o escopo de implementação (documente em `DECISIONS.md`) e investir nos itens abaixo:
- **ADRs** para as decisões difíceis (SQL vs NoSQL, monolito vs serviços, sync vs eventos).
- **Design de alta escala (documento):** arquitetura para 1 milhão de transações/minuto — caching, sharding, consistência eventual, e o que muda na semântica de idempotência nesse cenário.
- **Proposta de arquitetura orientada a eventos (EDA)** para o fluxo de liquidação.
- **Exercício de incidente (Anexo B):** conduza por escrito um post-mortem de um incidente de liquidações duplicadas.
- Git: estratégia de branching definida e justificada no README (Git Flow, trunk-based, GitHub Flow — e por que ela serve a *este* contexto).
- PRs escritos como quem ensina: um dev júnior do time conseguiria aprender lendo suas descrições?

---

## 7. IA como ferramenta de engenharia (`AI_USAGE.md`)

Não queremos o histórico completo das suas sessões. Queremos evidência de **engenharia da colaboração**:

1. **Specs/prompts estratégicos:** o plano ou especificação que você deu à IA (scaffolding, geração de massa de dados, refatoração) — não o log inteiro.
2. **Pelo menos um caso concreto em que a IA errou** (alucinou, gerou código inseguro ou sutilmente incorreto) e **como o seu processo detectou**: teste, review, aferição pelos golden cases?
3. **O que você decidiu não delegar à IA, e por quê.**

Fabricar este relatório é fácil. Sustentá-lo na defesa ao vivo, não.

---

## 8. Git e versionamento

O histórico do repositório deve contar uma história rastreável — e na defesa vamos abrir trechos dele e perguntar o porquê de decisões específicas. Requisitos por nível estão na seção 6. Hooks, rebase interativo e tags semânticas são bem-vindos, mas **não pontuam por si**: são meios, não fins.

---

## 9. Defesa técnica ao vivo — obrigatória para todos os níveis

Sessão de 60–90 minutos, remota, com a tela compartilhada no seu repositório:

1. **Walkthrough (10 min):** você apresenta a arquitetura e as decisões principais.
2. **Mudança ao vivo (30–40 min):** pediremos uma alteração real no seu código (não divulgada antes — ex.: um novo tipo de recebível com regra diferente, uma nova moeda, uma mudança na política de arredondamento). **IA liberada e incentivada**: queremos ver como você trabalha de verdade — como especifica, como valida o que a IA devolve, o que aceita e o que rejeita.
3. **Perguntas de decisão:** trechos do seu código, do seu histórico Git, do seu `REVIEW.md` e do seu `AI_USAGE.md`.
4. **Cenário de incidente (sênior+):** discussão de um problema em produção derivado do domínio do case.

Não conseguir explicar ou modificar o próprio código é eliminatório em qualquer nível — independentemente da qualidade do repositório entregue.

---

## 10. Entrega

1. **Repositório privado** (GitHub/GitLab) com convite para os avaliadores indicados no e-mail de envio do case.
2. **Prazo: até 5 dias corridos.** Esforço alvo: **8–16 horas** (júnior pode precisar de mais; está ok).
3. **`DECISIONS.md` obrigatório:** o que você deliberadamente cortou ou simplificou, e por quê. Priorização é critério de avaliação, não desculpa — um corte bem justificado vale mais que uma feature a mais mal feita.
4. O README é a cara do projeto: setup, design e decisões.

---

## 11. Rubrica de avaliação (pesos por nível)

| Critério | Júnior | Pleno | Sênior | Staff/TL |
|---|---:|---:|---:|---:|
| Corretude do motor (golden cases + testes) | 30% | 25% | 15% | 10% |
| Design de código (SOLID, camadas, clareza, frontend) | 25% | 20% | 15% | 10% |
| Domínio do negócio (precisão decimal, ACID, idempotência, auditoria) | 10% | 15% | 20% | 20% |
| Code review reverso (Anexo A) | 10% | 10% | 15% | 15% |
| Engenharia da colaboração com IA | 10% | 10% | 10% | 10% |
| Defesa ao vivo (autoria + mudança ao vivo) | 15% | 15% | 15% | 15% |
| Operação e arquitetura (observabilidade, resiliência, escala) | — | 5% | 10% | 20% |

---

## 12. Anti-padrões — contam contra você

- **Ponto flutuante binário para valores monetários** (`float`/`double`): eliminatório a partir de pleno.
- **Volume como proxy de qualidade:** microserviços para um case, camadas vazias, dezenas de testes de happy path gerados em massa.
- `README` ou `AI_USAGE.md` genéricos, claramente gerados sem revisão.
- Exceções engolidas silenciosamente ou erro respondido com `200 OK`.
- Não saber explicar um trecho do próprio repositório na defesa (eliminatório).

---

## Anexo A — Código para review (Fase 2)

> Contexto: endpoint de liquidação "gerado por IA e mergeado às pressas numa sexta-feira". Escreva o `REVIEW.md` sobre ele.

```typescript
// settlement.controller.ts
const BASE_RATE = 1.0; // taxa base mensal

app.post("/settlements", async (req: Request, res: Response) => {
  const { receivableId, currency } = req.body;

  const receivable = await db.queryOne(
    `SELECT * FROM receivables WHERE id = ${receivableId}`
  );

  const spread = receivable.type === "DUPLICATA" ? 1.5 : 2.5;
  const presentValue =
    receivable.face_value / Math.pow(1 + BASE_RATE + spread, receivable.term);

  let finalAmount = presentValue;
  if (currency === "USD") {
    const rate = await fxService.getLatestRate("USD");
    finalAmount = presentValue / rate;
  }

  try {
    await db.query(
      `INSERT INTO settlements (receivable_id, amount, currency)
       VALUES (${receivableId}, ${finalAmount.toFixed(2)}, '${currency}')`
    );
    await db.query(
      `UPDATE receivables SET status = 'SETTLED' WHERE id = ${receivableId}`
    );
  } catch (e) {
    // se falhar aqui, o insert já rodou, então segue o jogo
  }

  res.status(200).json({ ok: true, amount: finalAmount.toFixed(2) });
});
```

---

## Anexo B — Cenário de incidente (Staff / Tech Lead)

> Sexta-feira, 18h40. A mesa de operações reporta que três cedentes receberam a mesma liquidação **duas vezes**. O código do Anexo A está em produção há duas semanas. O time de plantão é você e um dev pleno.

Conduza, por escrito (1–2 páginas):

1. **Linha do tempo hipotética** do incidente, coerente com o código do Anexo A.
2. **Causa raiz provável** e como você a confirmaria com evidências (logs, banco, métricas).
3. **Ações imediatas** (contenção) vs. **correção definitiva**.
4. **Prevenção sistêmica:** o que muda no processo, no pipeline e no design para essa classe de erro não voltar — sem virar burocracia que trava o time.

---

**Boa sorte. Mostre-nos como você constrói — e opera — o futuro do mercado de crédito.**
