# DECISIONS — o que eu cortei, o que eu simplifiquei e por quê

## Como ler este documento

O alvo declarado desta entrega foi o **nível sênior** do enunciado (§6): operação — concorrência, observabilidade, resiliência, CI, C4 — e não o nível staff, que é explicitamente *parcialmente substitutivo* (menos código, mais decisão). Cada item abaixo é uma decisão de onde gastar as **8–16 horas** de esforço alvo (§10.2), tomada com a rubrica pública da §11 aberta ao lado.

Nada aqui é lista de pendência: onde eu cortei, eu digo o que ficou de fora, o que estou aceitando de risco e qual seria o gatilho para reverter a decisão. Onde não cortei, é porque o item pertence ao caminho do dinheiro e falhar nele custa mais que qualquer feature faltante.

---

## 1. Cortes de escopo

### 1.1 Frontend inteiro

**O que ficou de fora.** Todo o item 4.2 do enunciado: painel do operador com input do recebível e simulação do valor líquido em tempo real, e grid de transações com paginação server-side e filtros dinâmicos. Não existe diretório de frontend neste repositório — a entrega é backend, e o `README.md` diz isso na segunda linha.

**Por quê.** O que o painel consumiria já existe, está documentado e está testado:

- simulação sem efeito colateral em `POST /api/v1/simulations` (`SimulationController`), que devolve valor presente, deságio, prazo e as taxas aplicadas — é literalmente o *payload* da "simulação em tempo real";
- extrato com **paginação server-side de verdade** em `GET /api/v1/settlements/statement` (`SettlementStatementController` → `JdbcSettlementStatementQuery`), com filtro por período, cedente e moeda, `page`/`size`, e totais calculados sobre **o filtro inteiro, não sobre a página** — coberto por `SettlementStatementQueryIntegrationTest` (8 casos, incluindo página abusiva);
- contrato HTTP publicado em OpenAPI/Swagger UI (springdoc), com o fluxo ponta a ponta verificado em `CreditEngineApiIntegrationTest`.

Ou seja: o contrato que o frontend consumiria não é promessa, é código com teste. O que falta é a camada de apresentação.

**Quanto isso custa na rubrica.** O frontend é uma das quatro dimensões do critério **"Design de código (SOLID, camadas, clareza, frontend)"**, que vale **15%** no nível sênior. A rubrica não fatia o critério, então não dá para cravar o número; na prática eu zero a dimensão de frontend e disputo as outras três (SOLID, camadas, clareza) dentro desses 15%. É o maior preço que esta entrega paga.

**Por que ainda assim foi o melhor uso do tempo.** No eixo sênior, os critérios que crescem são **domínio do negócio (20%)** e **operação e arquitetura (10%)**, e ambos vivem inteiramente no backend: optimistic locking com teste de corrida, idempotência com unicidade no banco, cotação congelada na auditoria, disjuntor no provedor de câmbio, CI com linter. Um painel React de 8 horas somaria uma fração dos 15% de design e me obrigaria a cortar exatamente o que o nível sênior mais pesa. Cortar o frontend é perder pontos num critério; cortar concorrência ou auditoria seria perder o case.

**O que eu faria com mais tempo / em produção.** Em produção o painel não é opcional — é o produto para a mesa. SPA pequeno (React + TypeScript, cliente tipado a partir do `/v3/api-docs`), duas telas: simulação com *debounce* chamando `/simulations` e grid paginado consumindo `/settlements/statement` com os parâmetros que já existem. Sem estado global — os dois casos são request/response. Estimativa honesta: 6–10 horas para ficar apresentável.

**Risco aceito.** O avaliador não vê o sistema funcionando por uma tela — vê por `curl`/Swagger. Assumo que a sequência de `curl` do `README.md` cumpre esse papel e que a defesa ao vivo acontece sobre o código.

### 1.2 Itens do nível staff — não perseguidos

**O que ficou de fora.** Os quatro artefatos da §6 staff: **ADRs formais** para as decisões difíceis, **documento de design para 1 milhão de transações/minuto** (caching, sharding, consistência eventual e o que muda na semântica de idempotência nessa escala), **proposta de arquitetura orientada a eventos** para o fluxo de liquidação, e o **post-mortem do Anexo B** (liquidações duplicadas na sexta às 18h40).

**O que isso não inclui.** O diagrama C4 (níveis 1 e 2) é requisito do nível **sênior**, não do staff, e está entregue em `ARCHITECTURE.md`. A fronteira aqui é outra: ficou de fora a documentação de decisão que **substitui** implementação.

**Por quê.** O próprio enunciado marca esse nível como *parcialmente substitutivo*: quem o persegue **reduz o escopo de implementação** em troca de decisão documentada. Eu escolhi o outro lado do balanço — entregar o eixo de operação implementado e verificável — então esses itens não são requisitos do nível que eu declarei perseguir, e listá-los como "próximos passos" seria fingir que a entrega mira dois níveis ao mesmo tempo.

**O que eu faria com mais tempo.** Nesta ordem: (1) post-mortem do Anexo B, porque é o mais barato e o mais próximo do que já existe — o `REVIEW.md` já contém a linha do tempo, as três janelas de corrida e a prevenção sistêmica, faltando o formato de incidente com contenção e comunicação; (2) três ADRs curtos (SQL vs. NoSQL, JDBC vs. JPA, síncrono vs. eventos), que hoje vivem espalhados entre `SPEC.md`, `README.md` e este documento; (3) o design de alta escala, que é o mais caro e o que menos aproveita código existente.

**Risco aceito.** Se a banca estiver avaliando este repositório na barra staff, eu perco os **20%** de "operação e arquitetura" desse nível por falta de documento, mesmo tendo implementação. Assumo esse risco porque o alvo está declarado desde o `SPEC.md` §1.

### 1.3 Spring JDBC em vez de JPA/Hibernate

Este não é corte de qualidade — é **troca de custo**, e faço questão de chamar pelo nome.

**O que eu comprei.** SQL explícito e parametrizado (`JdbcClient`), optimistic locking que se lê como uma linha (`UPDATE receivables SET status='SETTLED', version=version+1 WHERE id=:id AND version=:version AND status='PENDING'` em `JdbcReceivableRepository`), extrato nascendo em SQL nativo com índices dedicados (`ix_settlements_assignor_settled_at`, `ix_settlements_currency_settled_at`) — o que o enunciado pede como diferencial em 4.1.6 — e nenhuma surpresa de *flush*, *dirty checking* ou `LazyInitializationException` no meio de uma transação de pagamento.

**O que eu paguei.** Mapeamento manual linha a linha em cinco adaptadores (`JdbcAssignorRepository`, `JdbcFxRateRepository`, `JdbcReceivableRepository`, `JdbcSettlementRepository`, `JdbcSettlementStatementQuery`), sem cache de primeiro nível, sem *lazy loading* e sem `@Version` de graça — a versão é coluna que eu mesmo incremento e confiro pelo número de linhas afetadas. `settlements` tem 21 colunas: o INSERT e o *row mapper* são verbosos por construção.

**Onde isso passa a doer.** Com **4 tabelas e 5 adaptadores** o custo é trivial e o ganho de clareza é alto. O ponto de virada, na minha experiência, é em torno de **8 a 10 agregados** — ou antes, se o mesmo agregado começar a ser projetado em mais de três consultas diferentes (aí o mapeamento manual vira duplicação) ou se aparecerem relacionamentos profundos que hoje não existem (aqui tudo é referência por `UUID`, sem grafo de objetos). Nesse cenário eu introduziria JPA para o CRUD dos agregados e **manteria JDBC para o caminho de liquidação e para os relatórios**, que são justamente onde ORM esconde o que eu preciso ver.

**Risco aceito.** Mais código de infraestrutura para manter e nenhuma proteção automática contra esquecer uma coluna nova no *row mapper*. Mitigação real: os testes de integração rodam contra PostgreSQL de verdade via Testcontainers — H2 não entra, porque dialeto, `NUMERIC` e locking são exatamente o que precisa ser exercitado.

### 1.4 Autenticação e autorização — não existem

**O que ficou de fora, exatamente.** Não há `spring-boot-starter-security` no `pom.xml`, não há `SecurityFilterChain`, não há identidade de chamador em nenhum ponto. Qualquer requisição pode: liquidar qualquer recebível de qualquer cedente conhecendo o `UUID`; registrar cotação em `POST /api/v1/fx-rates` — isto é, **definir o câmbio da operação**; ler o extrato de qualquer cedente. O `assignorId` em `StatementFilter` é **filtro opcional de consulta, não escopo de permissão**: quem não passa o filtro vê tudo. A pergunta "quem pode liquidar recebível de qual cedente?" simplesmente não tem resposta no código.

**Por quê ficou fora do case.** O enunciado não pede autenticação em nenhum nível (§4, §6) e não há, no case, nem modelo de identidade (operador? mesa? cedente? administrador do fundo?) nem fonte de verdade de permissão. Autenticação sem esse modelo é decoração: eu acabaria inventando um usuário fictício e um `@PreAuthorize` sem regra de negócio por trás, gastando tempo de implementação para produzir um controle que não controla nada. Preferi deixar a ausência **declarada** a deixá-la **disfarçada**.

**Coerência com o `REVIEW.md`.** No Anexo A eu aponto a ausência de autorização (M5) como defeito, e mantenho. A diferença não é de régua, é de contexto, e vou defendê-la assim: naquele PR o endpoint **está em produção**, movimentando caixa, com `receivableId` vindo do body e sem nenhuma noção de quem chama — é IDOR em caminho de pagamento, e nenhum revisor deveria aprovar. Aqui é um case sem usuários, sem ambiente e sem dado real, com a ausência escrita em documento de entrega. O que eu recusaria num PR de produção é um endpoint de dinheiro **aberto sem que ninguém tenha decidido isso**; o que eu aceito num case é um escopo **explicitamente não implementado**. O pecado do Anexo A é o silêncio, não o buraco.

**Onde entraria.** Em três pontos, e nenhum deles exige redesenho: (1) `SecurityFilterChain` com autenticação por token na borda, exigindo escopos distintos por operação — registrar cotação é privilégio de mesa, liquidar é privilégio de operador, ler extrato é privilégio de back-office; (2) **escopo por cedente nas consultas**, transformando `StatementFilter.assignorId` de filtro opcional em restrição obrigatória derivada do chamador, com o `assignor_id` que `settlements` já carrega (ele está lá justamente para trilha e filtro) — e a mesma checagem em `SettleReceivableCommand` antes de precificar; (3) auditoria de **quem** liquidou, que hoje não existe: `settlements` registra o que foi pago e com que taxa, mas não o operador. Em produção isso é requisito de compliance, não melhoria.

**Risco aceito.** Este repositório **não pode ser implantado em nenhuma rede acessível** como está. Não é "falta endurecer"; é pré-requisito de primeiro deploy, e está no topo da minha lista junto com o item 1.9.

### 1.5 Integração real de cotação (PTAX / mesa)

**O que ficou de fora.** Não existe cliente HTTP para PTAX, Bacen ou qualquer provedor. O que existe é `MockExternalFxRateSource` atrás da porta `ExternalFxRateSource`, com **latência e instabilidade configuráveis** (`credit-engine.fx.upstream.latency`, `failure-rate` no `application.yml`).

**Por quê.** O que importa no desenho não é o cliente HTTP — é o **comportamento sob falha**. Parsear JSON de provedor é trabalho conhecido e sem risco de arquitetura; o risco está na pergunta do enunciado (§6 sênior): *o que acontece se o provedor de taxa cai no meio de uma liquidação?* O mock permite exercitar de forma determinística o que a integração real só exercitaria em produção, num dia ruim:

- a porta distingue **dois modos de "não deu"**: `Optional.empty()` (o terceiro respondeu e não cota esse par — não gera retry nem conta para o disjuntor) e `FxRateSourceException` (falha técnica — conta para os dois). Tratar os dois igual leva a retentar uma pergunta cuja resposta não muda e a abrir o circuito por par mal configurado;
- **timeout por tentativa, retry no meio, disjuntor por fora**, compostos à mão em `FxConfiguration` (módulos programáticos do resilience4j, sem starter e sem AOP) para que a ordem seja legível em vez de implícita na precedência de aspectos;
- o caminho rápido é o banco: havendo cotação vigente e fresca, o terceiro não é consultado — e é por isso que **disjuntor aberto não derruba a operação**;
- validação do que o terceiro devolve: par trocado, vigência futura e cotação defasada são recusados, com tolerância de 5s de *skew* de relógio.

Tudo isso está em `ResilientFxRateProviderTest` (12 casos, sem Spring e sem banco) e em `FxUpstreamRefreshIntegrationTest` (PostgreSQL real, reproduzindo o golden case C3 com a taxa vinda do provedor e congelada na auditoria). Com provedor real, nada disso seria testável sem gravar tráfego.

**O que muda quando o provedor real entrar.** Uma classe nova implementando `ExternalFxRateSource` e configuração. Explicitamente **não muda**: a política de resiliência, a validação de vigência/defasagem, a regra de congelar a cotação na liquidação e a decisão de falhar com 503. O que eu adicionaria: *spread* de compra/venda (a pergunta 4 do `SPEC.md` §3, hoje sem resposta do negócio — o modelo atual tem uma cotação só por par), autenticação no cliente, e testes de contrato contra respostas gravadas do provedor.

**Risco aceito.** Um provedor real vai surpreender em detalhes que o mock não simula: campo mudando de nome, fuso implícito, feriado sem cotação, 200 com corpo de erro. Esses defeitos só aparecem na integração — mas aparecem no *adapter*, não no desenho.

### 1.6 Uma única migration `V1__initial_schema.sql`

**O que ficou de fora.** Histórico incremental. Todo o schema — quatro tabelas, CHECKs, UNIQUEs, índices e a trigger `trg_settlements_immutable` — está em **uma** migração Flyway, reescrita em vez de acrescentada ao longo das fatias.

**Por quê.** Migração existe para levar um banco **que já tem dados** de um estado ao próximo. Este projeto nunca foi implantado: não há nenhuma instância no mundo com `V1` aplicada que precise de `V2`. Um histórico de sete migrações aqui seria arqueologia da minha própria sessão de trabalho — e pior, esconderia o schema final atrás de um `diff` mental. Como está, o arquivo é legível de cabo a rabo e serve ao que o `REVIEW.md` afirma: *review de migration é review de regra de negócio*. As invariantes de pagamento estão num único lugar auditável.

**A regra que passa a valer no dia do primeiro deploy.** `V1` congela na hora em que for aplicada em qualquer ambiente compartilhado. Daí em diante: nunca editar migração aplicada, uma migração por mudança, `V2`, `V3`, e mudança de coluna em uso vira expand/contract (adiciona, migra dado, passa a ler, só então remove) — porque a partir desse dia existe um banco com dinheiro dentro que precisa sobreviver ao deploy. Em produção eu ainda ligaria `flyway.validate-on-migrate` com checksum e proibiria `clean` por configuração.

**Risco aceito.** Se alguém clonar este repositório, subir o banco, e eu depois editar `V1`, o Flyway falha por checksum divergente. É ruído previsível de projeto pré-implantação, resolvido recriando o banco local — e desaparece no primeiro deploy real.

### 1.7 Escopo de moedas: BRL e USD

**O que ficou de fora.** O enum `Currency` (`domain.money`) tem exatamente `BRL` e `USD` — confirmado no código e reforçado no banco pelos CHECKs `ck_receivables_face_currency` e pelos `CHAR(3)`. Não há moeda de terceira casa decimal, não há moeda sem casa decimal, não há tabela de moedas.

**Por quê enum e não `java.util.Currency`.** Adicionar moeda ao caixa de um FIDC é decisão de negócio (conta bancária, provedor de cotação, contabilidade), não dado de entrada livre. Enum fechado faz o compilador e o banco recusarem `EUR` até que alguém decida operar em euro; `java.util.Currency` aceitaria qualquer código ISO e transformaria erro de digitação em operação.

**O que o desenho já suporta.** Moeda nova com **2 casas decimais** é quase de graça: uma constante no enum, um valor no CHECK do schema, uma cotação no par. `FxRate` já é direcional (*1 unidade de `baseCurrency` = `rate` unidades de `quoteCurrency`*) e `convert()` decide multiplicar ou dividir pela direção do par, então cross-currency não assume BRL em lugar nenhum. O motor de precificação é indiferente à moeda.

**O que exigiria mudança de verdade.** Moeda que não tem 2 casas decimais — JPY (0 casas) é o caso clássico, e há moedas com 3. Hoje a escala 2 está *hardcoded* em três camadas, e eu sei em quais:

1. `RoundingPolicy.MONETARY_SCALE = 2` — a constante que todo arredondamento monetário usa. Precisaria virar propriedade **da moeda** (`Currency.scale()`), não do sistema;
2. `Money` — `zero()` e `rounded()` delegam àquela constante, e `toString()` também. Uma vez que a escala saia de `RoundingPolicy`, `Money` acompanha sem mudança estrutural;
3. `NUMERIC(19,2)` no schema para `face_value`, `present_value`, `discount_amount`, `settlement_amount` — e é aqui que dói: `NUMERIC(19,2)` **arredonda silenciosamente** na escrita. Guardar JPY nessa coluna funcionaria, mas guardar uma moeda de 3 casas perderia a terceira casa sem erro. A correção é `NUMERIC(19,4)` ou maior com a escala de exibição/arredondamento vindo da aplicação, e uma migração de tipo de coluna — barata hoje, caríssima com histórico de liquidações.

Ou seja: o suporte multimoeda genuíno é uma mudança de **um** ponto na aplicação (`RoundingPolicy`) mais **uma migração** de tipo no banco. Não é refatoração; é um dia de trabalho com teste de golden case por moeda.

**Risco aceito.** Se a mudança ao vivo da defesa for "adicione JPY", eu vou tocar em `Currency`, `RoundingPolicy` e no schema — e não em 30 arquivos. Esse é o resultado que a centralização da política de arredondamento foi comprada para dar.

### 1.8 Cobertura de teste: estratégia em vez de percentual

**O que ficou de fora.** Não há métrica de cobertura no build — nem JaCoCo, nem *gate* de percentual. São **90 invocações** (`mvn test`, confirmado nos relatórios do surefire: 90 testes, 0 falhas, 0 erros, 0 ignorados), distribuídas por decisão e não por arquivo.

**Onde eu gastei teste, e por quê.** Nos quatro lugares onde errar custa dinheiro:

1. **Golden cases ao centavo** (`GoldenCasesTest`, 4): C1/C2/C3 com valores esperados conferidos por cálculo independente, mais a invariante `face = valor presente + deságio`. Se o motor não bate ao centavo, nada mais importa;
2. **Bordas de arredondamento e de prazo** (`PricingEngineTest`, 19 entre `Rounding`, `Fx` e `InvalidInput`; `TermCalculatorTest`, 6; `MoneyTest`, 4): empates de HALF_EVEN, prazo 0, 1 centavo, prazo longo, 30/360 com fração para cima, vencido recusado, FX obrigatório e direção do par. É a classe de erro que não aparece em teste de happy path e aparece no fechamento contábil;
3. **Concorrência e ACID com banco real** (`SettlementServiceIntegrationTest`, 11): 8 threads no mesmo recebível → exatamente 1 pagamento; replay da chave; chave reusada com payload diferente; rollback quando o câmbio cai; taxa defasada e taxa futura recusadas; e a imutabilidade **barrada pela trigger** do PostgreSQL. Mock de repositório não valida UNIQUE, isolamento nem trigger — por isso Testcontainers e não H2;
4. **Contrato HTTP** (`SettlementControllerTest`, 10 + `CreditEngineApiIntegrationTest`, 9 + `SettlementStatementQueryIntegrationTest`, 8): a tabela de status (201/200/400/404/409/503) com o handler real, o fluxo ponta a ponta do C3 e a paginação/totais do extrato no banco. Mais os 12 de `ResilientFxRateProviderTest` e 2 de `FxUpstreamRefreshIntegrationTest` para o comportamento sob falha do câmbio, e 5 de `CorrelationIdFilterTest` para o rastro de log — inclusive os dois casos negativos: cabeçalho forjado pelo cliente descartado e contexto que não vaza entre requisições da mesma thread.

**O que eu deliberadamente não testei.** Getters e `equals` de records; mapeamento trivial de DTO (`MoneyView`, `AssignorView` e companhia — se quebrarem, quebram no teste de API, que é onde importa); caminhos de configuração (`PricingConfiguration`, `OpenApiConfiguration`, ligar/desligar bean por propriedade); o `MockExternalFxRateSource` em si, que é ferramenta de teste e não código de negócio; e `FxUpstreamHealthIndicator`, cujo comportamento interessante (disjuntor aberto → `DEGRADED`) é decisão de uma linha.

**Por quê.** Porque o enunciado lista como anti-padrão (§12) exatamente *"dezenas de testes de happy path gerados em massa"*, e porque cobertura é proxy, não objetivo: eu chegaria a 90% adicionando trinta testes de getter sem aumentar em nada a confiança de que a liquidação não paga duas vezes. Com IA, gerar esses trinta testes custa dois minutos — o que é precisamente o motivo de não valerem nada como evidência. Cada teste que existe aqui eu sei defender: o que ele protege e que bug ele pegaria.

**Risco aceito.** Não sei meu percentual de cobertura, e há caminhos de erro secundários sem teste (por exemplo, todos os `errorCode` do `ApiExceptionHandler` individualmente). Em produção eu ligaria JaCoCo — não como *gate* de número, mas como **relatório para achar caminho de dinheiro sem teste**, que é a única pergunta útil que cobertura responde.

### 1.9 Operação em cluster: tracing, rate limiting e deploy

Agrupo aqui três ausências que verifiquei no repositório e que têm a mesma justificativa e o mesmo gatilho — não são features do case, são requisitos do dia em que isto roda para alguém.

**Um item saiu desta lista, e o motivo importa.** O log estruturado em JSON com correlação por requisição e por chave de idempotência estava aqui como corte — e estava errado, porque o `SPEC.md` §5 o declara como **critério de aceite**. Isso não era corte, era promessa não cumprida, e as duas coisas se tratam de forma diferente: corte se declara, promessa se cumpre ou se retira do documento. Cumpri (`logback-spring.xml` com encoder JSON + `CorrelationIdFilter` colocando `correlationId` e `idempotencyKey` no MDC, com `X-Correlation-Id` devolvido em toda resposta). Deixar passar seria repetir, no meu próprio repositório, o defeito que o `REVIEW.md` cobra do Anexo A: documento afirmando o que o código não faz.

**O que ficou de fora, exatamente.**

- **Tracing distribuído (OpenTelemetry / Micrometer Tracing).** Ausente do `pom.xml`. Com um serviço, um banco e um provedor externo, o *span* que interessa — a chamada ao câmbio — já está medido por métrica (`credit_engine.fx.lookups{outcome}`) e pelos contadores do disjuntor. Tracing paga quando existe salto entre serviços; aqui pagaria overhead para redesenhar o que a métrica já responde.
- **Rate limiting.** Não existe. A proteção que existe é contra **saturação por dependência lenta**, não contra volume de cliente: pool dedicado sem fila para o câmbio (`SynchronousQueue`, `AbortPolicy`, `max-concurrent-calls: 8`) e `maximum-pool-size: 10` com `connection-timeout: 3000` no Hikari. Limite por chamador exige identidade de chamador — que não existe (item 1.4). Fora isso, é decisão de borda: eu colocaria no gateway/ingress, não no serviço.
- **Deploy (Helm, manifests, pipeline de release).** Há `Dockerfile` multi-stage e `docker-compose.yml`; a CI (`.github/workflows/ci.yml`) roda lint e testes e **não publica imagem nem implanta**. Manifests de Kubernetes sem cluster, sem *secret manager* e sem ambiente são ficção versionada: eu escreveria um YAML que nunca foi aplicado e que envelheceria antes do primeiro uso.

**O gatilho comum.** Os três entram junto com o item 1.4 (autenticação), no mesmo momento: **o primeiro deploy em ambiente compartilhado**. Nessa ordem: autenticação → deploy com *secret* de verdade → rate limiting no gateway → tracing quando existir o segundo serviço.

**Risco aceito.** Um incidente hoje é investigado filtrando o log JSON por `correlationId` ou `idempotencyKey` e olhando as métricas do Actuator (`/actuator/health`, `/actuator/prometheus`). Dá para conduzir o plantão do Anexo B assim; o que falta é o salto entre serviços que o tracing resolveria — e ele não existe neste desenho.

### 1.10 Ciclo de vida do recebível: cancelamento e estorno sem caso de uso

**O que ficou de fora.** `ReceivableStatus` tem `CANCELLED` e o CHECK `ck_receivables_status` aceita o valor, mas **não existe operação que produza esse estado**: não há endpoint de cancelamento, nem serviço, nem transição no domínio. Em todo o código de produção o valor aparece uma única vez — na declaração do próprio enum. Do mesmo modo, o `SPEC.md` §2.7 define que correção de liquidação se faz por **evento compensatório (estorno)**, e o estorno **não está implementado**: não há `POST /settlements/{id}/reversal`.

**Por quê.** As duas coisas dependem de respostas que eu não tenho (perguntas 5 e 6 do `SPEC.md` §3): quem pode estornar, em que janela, e o estorno reverte ao câmbio original ou ao câmbio do dia? Implementar estorno com premissa inventada seria pior que não implementar, porque criaria um segundo caminho de dinheiro — o mais perigoso de todos, o que devolve valor — governado por um palpite meu. E estorno sem autorização (item 1.4) é um endpoint que desfaz pagamento aberto na rede.

**Por que o estado fica no enum mesmo sem uso.** Porque ele já **protege**: `Receivable.settled()` só aceita `PENDING`, e o UPDATE condicional exige `status = 'PENDING'`. Quando o cancelamento existir, um título cancelado já é inelegível à liquidação por construção, sem mudar o caminho de pagamento. É extensão preparada, não código morto — e eu prefiro declarar isso aqui a deixar alguém descobrir na leitura.

**O que eu faria.** Cancelamento é simples e barato (transição `PENDING → CANCELLED` com optimistic locking, espelhando o que a liquidação já faz). Estorno vem depois, como **lançamento novo** referenciando a liquidação original, jamais como UPDATE — a trigger `trg_settlements_immutable` está no banco exatamente para garantir que ninguém "conserte" história.

**Risco aceito.** Um título cadastrado por engano fica `PENDING` para sempre e continua elegível à liquidação. Em produção isso é operação manual no banco — inaceitável; num case, é escopo declarado.

### 1.11 Paginação só onde há volume

**O que ficou de fora.** Paginação server-side existe **apenas no extrato** (`StatementFilter` com `page`/`size`, totais sobre o filtro inteiro). O histórico de cotações (`GET /api/v1/fx-rates`) usa `limit` com *clamp* de 1 a 200 — teto, não paginação. Cedentes e recebíveis **não têm listagem**: só `POST`, `GET /{id}` e `GET /receivables/{id}/settlement`.

**Por quê.** Paginação foi para onde o volume cresce sem limite e onde o enunciado pede (grid de transações, pleno+): liquidações acumulam para sempre, e o `SPEC.md` §5 fixa "extrato paginado sobre ~100 mil liquidações, p95 < 500 ms" com SQL nativo e índice por `(assignor_id, settled_at DESC)`. Histórico de cotação é consultado como "as últimas N" — teto resolve. Listagem de cedentes e recebíveis não foi inventada porque não há tela que a consuma (item 1.1): endpoint sem consumidor é camada vazia, anti-padrão da §12.

**O que eu faria / risco aceito.** Com o painel, listagem de recebíveis por cedente e status — **já paginada desde o primeiro commit**, porque paginação retrofitada em API pública é mudança incompatível de contrato. Hoje um cedente com muitos recebíveis não tem como ser navegado pela API; nenhum cliente sofre, porque nenhum cliente existe.

---

## 2. Simplificações que **não** são cortes

Coisas que parecem falta e são decisão. A diferença: eu não mudaria nenhuma delas se tivesse mais tempo — mudaria se o negócio mudasse.

- **Taxa base fixa por configuração, não tabela versionada.** `PricingProperties.baseMonthlyRate` (default `0.01` = 1,00% a.m., igual aos golden cases) atrás de `BaseRateProvider`, implementado por `FixedBaseRateProvider`. O ponto não é o valor estar em YAML — é a interface **já receber a data de referência** (`monthlyBaseRate(LocalDate)`) e a taxa efetivamente aplicada ser **gravada em cada liquidação** (`monthly_base_rate`, `monthly_spread`). Isso é o que torna uma liquidação antiga reproduzível depois de a mesa retunar a taxa. Trocar por tabela histórica ou CDI importado muda um bean, não o motor. Guardar tabela de vigência sem ninguém para alimentá-la seria estrutura sem dado.
- **Histórico de cotação append-only, sem endpoint de correção.** `fx_rates` só recebe INSERT, com `uk_fx_rates_pair_effective_at` por (par, vigência). Não existe `PUT` nem `DELETE`. Cotação errada se corrige **publicando uma nova** com vigência mais recente — nunca reescrevendo a que já precificou uma liquidação. E a colisão de chave única na escrita **não é tratada como erro**: significa que outra instância gravou a mesma cotação primeiro, o que num histórico append-only é o resultado desejado.
- **Nenhum plano B com taxa velha: falha com 503 de propósito.** Sem cotação vigente, ou com cotação além de `credit-engine.fx.max-staleness` (12h), a liquidação cross-currency falha com `FX_RATE_UNAVAILABLE`. Não há *fallback* 1:1, nem "usa a última que tiver". Taxa defasada em dia de volatilidade não é degradação graciosa, é prejuízo silencioso — e prejuízo silencioso é pior que indisponibilidade, porque ninguém é avisado. Quem decide operar com incerteza cambial é a mesa, não o `catch`.
- **`MockExternalFxRateSource` nasce desligado.** `credit-engine.fx.upstream.enabled` tem default `false`; o `docker-compose.yml` liga explicitamente com `CREDIT_ENGINE_FX_UPSTREAM_ENABLED=true` no ambiente de demonstração. Mock que sobe sozinho acaba precificando operação real — e o default seguro é o que vale quando alguém esquece de configurar. Desligado, o provedor de cotação é só o histórico do banco (`StoredFxRateProvider`).
- **Imutabilidade de `settlements` no banco, não só na aplicação.** A trigger `trg_settlements_immutable` rejeita UPDATE e DELETE com `restrict_violation`. Não é redundância desconfiada do meu próprio código: é reconhecer que a aplicação não é o único cliente do banco. Script de correção às pressas, rotina de ETL e console de DBA não passam pelo `SettlementService` — passam pela trigger. Está coberta por teste de integração, que é a única forma de provar isso.
- **O extrato atalha uma camada, de propósito.** `SettlementStatementController` chama direto a porta de leitura (`SettlementStatementQuery` / `JdbcSettlementStatementQuery`), sem service intermediário. Autorizado pelo item 4.1.7 do enunciado, e a alternativa seria um service que só repassa a chamada — camada vazia, anti-padrão da §12. Relatório não tem regra de negócio a orquestrar; tem SQL e índice.

---

## 3. O que eu não cortaria de jeito nenhum

Inegociáveis mesmo com prazo estourando, porque o custo de errar neles não é retrabalho — é dinheiro errado no lugar errado.

- **Tipo de dinheiro sobre `BigDecimal`.** `Money` não tem construtor a partir de `double`/`float`, e o Checkstyle **proíbe** `float`/`double` e `new BigDecimal(<literal numérico>)` no código de produção, na fase `validate` do build. Motivo de negócio: binário não representa 0,01; o erro aparece como centavo perdido que em milhares de liquidações vira divergência contábil e litígio. O enunciado chama isso de eliminatório a partir de pleno, e concordo — mas eu não faria diferente sem enunciado. Regra que depende de disciplina humana volta no primeiro sábado de pressa; por isso virou *gate* de build.
- **Atomicidade e idempotência da liquidação.** Uma `@Transactional` cobrindo o UPDATE condicional do recebível e o INSERT da liquidação: ou as duas coisas, ou nenhuma. `Idempotency-Key` obrigatória, com *fingerprint* do pedido para distinguir retry legítimo de chave reaproveitada. Motivo de negócio: retry de rede e duplo clique são certeza, não hipótese, e liquidação pela metade significa cedente recebendo duas vezes — o incidente do Anexo B. Nunca vou trocar isso por uma feature.
- **Unicidade como invariante no banco.** `uk_settlements_receivable` e `uk_settlements_idempotency_key`, mais o CHECK `ck_settlements_fx_required_when_cross_currency`. Motivo de negócio: validação em memória não sobrevive a duas instâncias da aplicação, e a segunda instância é o cenário normal em produção. A unicidade no banco é a rede que não depende do meu código estar correto — inclusive contra um bug futuro que eu mesmo vá introduzir.
- **Cotação congelada na auditoria.** A taxa é resolvida **uma vez** por liquidação e **copiada** para a linha (`fx_rate`, `fx_base_currency`, `fx_quote_currency`, `fx_rate_effective_at`, `fx_rate_source`) — não referenciada por chave estrangeira. Motivo de negócio: contestação de cedente é cenário real, e liquidação irreproduzível é liquidação indefensável. Referenciar a tabela de taxas deixaria o extrato reinterpretando qual taxa "seria" a vigente — reescrever o passado por *join*.
- **Erro nunca responde 200.** `ApiExceptionHandler` concentra o mapa domínio → HTTP (400/404/409/422/503/500) com corpo `application/problem+json` e `errorCode` estável; nenhuma exceção é engolida, e o Checkstyle bane `catch` vazio sem exceção por comentário. Motivo de negócio: com 200 mentiroso, retry deixa de ser seguro, dashboard mente e integração marca "pago" o que não pagou. O comentário `// se falhar aqui, o insert já rodou, então segue o jogo` do Anexo A teria falhado o *gate* antes de qualquer revisor humano ler o PR.

---

## 4. Ordem de priorização adotada

Cinco fatias verticais, uma por branch, mergeadas com `--no-ff` como PR (confirmável em `git log --oneline`):

| # | Branch | O que entrou | Por que primeiro/depois |
|---|--------|--------------|--------------------------|
| 1 | `feat/pricing-engine` | `SPEC.md`, motor com Strategy, `Money`/`RoundingPolicy`, `TermCalculator`, golden cases ao centavo | Precisão numérica e política de arredondamento são o que mais dói mudar depois: erram em silêncio e contaminam todo valor já gravado. Premissa escrita antes do código porque decidir arredondamento durante a implementação é decidir por acidente. |
| 2 | `feat/settlement-persistence` | `V1__initial_schema.sql`, adaptadores JDBC, liquidação atômica e idempotente, teste de 8 threads, imagem e compose | Modelo de dados e invariantes vêm em segundo porque migrar schema com histórico de liquidações é o retrabalho mais caro que existe. UNIQUE, CHECK e trigger nasceram junto com a tabela, não como endurecimento posterior. |
| 3 | `feat/rest-api-statement` | API REST com OpenAPI, tradutor único de erro em HTTP, extrato paginado em SQL nativo, `REVIEW.md` | Contrato HTTP depende do domínio estar fechado — definir status e payload antes das invariantes seria documentar intenção. O `REVIEW.md` veio aqui porque só faz sentido apontar defeito do Anexo A com a correção existindo no repositório. |
| 4 | `feat/fx-resilience-ci` | Provedor externo com timeout/retry/disjuntor, `FxUpstreamHealthIndicator`, métricas, CI e Checkstyle | Resiliência é sobre o que acontece quando o correto falha: sem o correto pronto, não há o que proteger. Documento e pipeline são as coisas mais baratas de acrescentar no fim — e as únicas que não corrompem dado se entrarem tarde. |
| 5 | `docs/architecture-decisions` | `ARCHITECTURE.md` (C4 níveis 1-2), este documento, `AI_USAGE.md` e o log estruturado com correlação | Diagrama e decisão se escrevem melhor com o código estabilizado: escritos antes, descreveriam intenção e envelheceriam a cada fatia. O log estruturado entrou aqui não por prioridade, mas porque a revisão destes documentos expôs uma promessa do `SPEC.md` sem código atrás (ver 1.9) — e o mesmo olhar pegou uma divergência de relógio entre validador e aplicação, que trocava o `400` contratado por `422` na virada do dia. |

**O critério, em uma frase:** primeiro o que é caro de consertar depois (precisão numérica, modelo de dados, invariantes de pagamento), por último o que é barato de acrescentar (documentação, pipeline, linter). Corolário incômodo e assumido: **o frontend está na ponta mais barata dessa régua** — é a camada que se acrescenta sem mexer em nada do que já existe, e foi por isso que perdeu.

**Se tivesse uma sexta fatia:** o **painel mínimo do operador** — uma tela de simulação consumindo `POST /api/v1/simulations` com *debounce* e um grid consumindo `GET /api/v1/settlements/statement` com os filtros e a paginação que já estão testados.

Por que essa e não outra: é o único item da rubrica em que esta entrega tira **zero** (item 4.2 do enunciado, dimensão de frontend do critério de 15%), enquanto os demais eixos já têm implementação e evidência. Marginalmente, é onde cada hora rende mais nota. E é a fatia mais barata que existe agora, justamente porque a fatia 3 entregou o contrato: a tela não precisa de nenhum endpoint novo nem de nenhuma mudança no backend.

A alternativa que considerei e descartei foi **autenticação e escopo por cedente** (item 1.4). Ela é mais importante que o painel em qualquer medida de produção — e continuaria sendo a primeira coisa que eu faria antes de um deploy real. Mas num case sem usuários, sem ambiente e sem modelo de identidade definido, ela custa mais horas e me daria menos pontos do que a única dimensão da rubrica que está zerada. Prioridade de entrega e prioridade de produção não são a mesma lista — e é exatamente isso que este documento existe para deixar explícito.
