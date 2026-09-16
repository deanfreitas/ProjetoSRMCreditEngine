# AI_USAGE — engenharia da colaboração com IA

Este documento não é o log das sessões. É o registro de **como eu especifiquei**, **como eu verifiquei** e **onde a IA errou** durante a construção deste repositório. Tudo o que está aqui é rastreável: os quatro casos de erro descritos na seção 3 deixaram marca no código, nos testes ou no histórico Git, e podem ser abertos na defesa.

Ferramenta usada: assistente de código com acesso ao repositório e ao terminal (leitura de arquivos, execução de `mvn`, execução de Docker), operando em ciclos de especificação → geração → verificação por mim.

---

## 1. O modelo de colaboração que eu adotei

Tratei a IA como **um desenvolvedor rápido, incansável e sem responsabilidade pelo resultado**. Isso define os dois lados da divisão de trabalho:

- **Meu**: as decisões de negócio e de invariante — premissas das ambiguidades, modelo de dados, ordem das etapas dentro da transação, valores esperados dos testes, critério de aceite.
- **Da IA**: o trabalho de volume sob critério de aceite explícito — implementação a partir de desenho fechado, testes de borda a partir da lista de casos que eu enumerei, redação de documento a partir de brief detalhado, configuração de ferramenta.

A regra que eu apliquei o tempo todo: **a IA pode escrever qualquer coisa, desde que a verificação de que aquilo está certo não venha dela.** Isso é o que aparece na seção 4.

---

## 2. Especificações estratégicas (o que eu de fato escrevi como prompt)

Não vou colar prompts inteiros. Vou mostrar os **três padrões de especificação** que fizeram diferença, porque eles são o que eu levaria para qualquer outro projeto.

### 2.1 Fechar o escopo de arquivos antes de pedir código

Todo pedido de geração começou com uma cerca. Exemplo real, do pedido que gerou o linter e o pipeline de CI:

> Crie EXATAMENTE estes arquivos e NENHUM outro: `.github/workflows/ci.yml`, `config/checkstyle/checkstyle.xml`.
> PROIBIDO alterar `pom.xml`, qualquer arquivo em `src/`, `README.md`, `SPEC.md`, `REVIEW.md`, `docker-compose.yml`, `Dockerfile`. PROIBIDO fazer commit. Eu faço a ligação do plugin no `pom.xml` depois.

Isso não é burocracia. Sem a cerca, o padrão de comportamento é "resolver o problema do jeito mais curto", e o jeito mais curto costuma ser mexer no lugar errado — relaxar uma configuração, reescrever um teste, tocar um arquivo que outra parte do trabalho estava usando. Cerca estreita também torna o meu review viável: eu sei exatamente qual diff abrir.

### 2.2 Critério de aceite executável, e o que fazer quando ele falhar

Mesmo pedido, trecho que eu considero o mais importante do prompt inteiro:

> Valide rodando o Checkstyle sem alterar o pom: `mvn -B org.apache.maven.plugins:maven-checkstyle-plugin:3.5.0:check -Dcheckstyle.config.location=config/checkstyle/checkstyle.xml`. Itere até ZERO violações no `src/main/java` atual.
> **Se alguma violação apontar defeito real no código, NÃO corrija o código (não é seu escopo): relate no resultado final. Se apontar falso positivo, ajuste a regra.**

O critério de aceite precisa ser um comando com saída objetiva ("zero violações"), não um adjetivo ("código limpo"). E precisa vir acompanhado da instrução de **o que fazer quando o critério não bate** — senão a IA escolhe sozinha, e ela escolhe o caminho que deixa o build verde. Foi exatamente o que aconteceu no caso 3.1.

### 2.3 Desenho antes de implementação, com o "por que" já decidido

Para a resiliência do câmbio, eu não pedi "adicione retry e circuit breaker". Eu especifiquei o desenho e as razões, e deixei para a IA a mecânica:

> O caminho rápido é o banco: se existe cotação vigente e fresca, não se sai para a rede. A composição é disjuntor → retry → timeout, **nessa ordem**, para que uma requisição conte como UMA observação no disjuntor e o teto de tempo valha por tentativa. Composição programática, sem starter com AOP. "Não coto esse par" é resposta válida: não gera retry nem conta para o disjuntor. Não existe plano B com taxa velha — falha vira 503.

Quando o desenho vem decidido, a IA é muito boa. Quando o desenho vem aberto, ela devolve o default da internet: anotação `@Retry` em cima do método, ordem de aspectos implícita, e um `orElse(ultimaTaxaConhecida)` no fim — que é precisamente o bug que eu não quero neste domínio.

---

## 3. Onde a IA errou — quatro casos concretos e como o processo pegou

### 3.1 A IA silenciou a própria regra que o `REVIEW.md` promete (o caso mais grave)

**O que aconteceu.** Pedi um ruleset de Checkstyle com limite de 140 colunas e mandei iterar até zero violações. Havia 4 linhas longas no código de produção (`SimulationController`, `SettlementController` em duas linhas, `SettlementService`). A IA chegou a zero violações — criando um `config/checkstyle/suppressions.xml` que **isentava controllers e repositórios da regra de comprimento de linha**. O relatório final reportou "ZERO violações, BUILD SUCCESS", e era verdade.

**Por que é sutil.** O critério de aceite foi cumprido ao pé da letra. O build estava verde. A violação tinha desaparecido do relatório. Só que a regra havia sido esvaziada justamente nas camadas onde ela seria exercida — e o `REVIEW.md` deste repositório afirma, na seção de prevenção sistêmica, que o lint é o que impede os defeitos do Anexo A de voltarem. Um arquivo de supressão por pacote transforma essa afirmação em promessa vazia. Pior: supressão nasce pequena e cresce, porque o próximo desenvolvedor que bate na regra aprende que existe um lugar onde se pede dispensa.

**Como eu detectei.** Não pelo relatório — o relatório dizia que estava tudo bem. Detectei porque **eu leio o diff arquivo por arquivo** e o `suppressions.xml` não estava no escopo que eu havia autorizado (seção 2.1: "crie EXATAMENTE estes arquivos"). Um arquivo fora da cerca é sinal de que a IA resolveu o problema por um caminho que eu não aprovei. Removi o arquivo e rodei o Checkstyle limpo: as 4 violações reapareceram, nomeadas linha por linha.

**Como eu corrigi.** Quebrei as quatro linhas. O repositório não tem arquivo de supressão nenhum — isso é verificável em `config/checkstyle/`. Está escrito no commit da fatia: "as quatro linhas que passavam do limite foram quebradas em vez de isentadas: exceção por pacote esvazia a regra".

**A lição que eu levo.** A IA otimiza para o critério de aceite que você escreveu, não para a intenção por trás dele. Onde a intenção importa mais que a métrica, o critério tem que incluir a proibição do atalho. Hoje eu escreveria: "sem arquivo de supressão; se houver violação, relate — não isente".

### 3.2 Status HTTP certo, corpo de erro errado

**O que aconteceu.** Na fatia da API, o `ApiExceptionHandler` traduz exceção de domínio em `problem+json` com um campo `errorCode`. Os testes de status passavam. Mas parte das respostas de erro saía **sem** o `errorCode`: o advice padrão do próprio Spring Boot vencia o meu por precedência, devolvendo o corpo dele com o status correto.

**Como eu detectei.** Pelo teste de contrato que asserta o **corpo** da resposta, não apenas o código de status. Se eu tivesse aceitado a suíte gerada de primeira — que verificava `status == 409` e considerava o caso coberto — o defeito teria passado, e o consumidor da API descobriria em produção que o campo que ele usa para decidir o tratamento simplesmente não vem em alguns erros.

**Correção.** Precedência explícita no advice. O caso está coberto por teste que verifica `errorCode` para cada família de erro.

### 3.3 Rigor a mais na vigência da cotação: relógios diferentes

**O que aconteceu.** O `ResilientFxRateProvider` valida o que o provedor externo responde antes de aquilo virar base de pagamento — entre outras coisas, recusa cotação com **vigência futura** (taxa que ainda não vale não pode precificar liquidação de agora). A implementação comparava a vigência devolvida com um instante capturado antes da chamada. Resultado: a cotação era recusada porque vinha alguns milissegundos "no futuro".

**Como eu detectei.** O teste de integração contra PostgreSQL real (`FxUpstreamRefreshIntegrationTest`) falhou com `FxRateUnavailable`. Os testes unitários com clock fixo passavam — a falha só aparece quando existe tempo real correndo entre os dois pontos. É o argumento de por que eu não troquei Testcontainers por um duplo em memória.

**Correção.** Tolerância explícita de 5 segundos de skew de relógio, com teste próprio. Note que a correção não foi afrouxar a validação: vigência realmente futura continua sendo recusada.

### 3.4 O golden case que pegou um número errado no fluxo cross-currency

**O que aconteceu.** O teste de liquidação usando a taxa trazida do provedor externo falhou: esperado `17.094,67`, obtido `16.603,94`. A diferença de quase quinhentos dólares não é arredondamento — era o cenário montado no teste que não reproduzia o golden case C3 ao pé da letra, e portanto precificava outra operação.

**Como eu detectei.** Porque **o valor esperado nunca foi gerado pelo motor**. Os três golden cases e os casos de borda de arredondamento foram conferidos por cálculo independente (`bc` com 30 casas) antes de entrarem como assertiva. Esse é o detalhe que faz a diferença: se eu tivesse pedido à IA para "gerar os testes do motor", ela rodaria o código, colheria a saída e escreveria `assertEquals(saída, saída)` — uma suíte verde que prova apenas que o motor é consistente com ele mesmo. O enunciado dá os três valores de aferição justamente porque essa armadilha é o modo de falha padrão de teste gerado por máquina.

---

## 4. O que eu decidi não delegar, e por quê

**As premissas das ambiguidades do enunciado** (`SPEC.md`). Unidade do prazo, origem da taxa base, política de arredondamento e qual câmbio vale na liquidação foram deixados ambíguos de propósito. Escolher ali é decisão de negócio com consequência em dinheiro — arredondar em etapa intermediária em vez de só no fim muda o valor pago ao cedente. A IA responde qualquer uma das opções com a mesma confiança, e eu é que teria de defender a escolha. Delegar isso seria delegar a autoria.

**Os valores esperados dos testes.** Conferidos fora do sistema, com calculadora de precisão arbitrária. Ver 3.4: um oráculo derivado do próprio código não é oráculo.

**O modelo de dados e as invariantes do banco.** Unicidade por recebível **e** por `idempotency_key`, `CHECK` exigindo cotação em cross-currency, trigger que rejeita `UPDATE`/`DELETE` em `settlements`, `NUMERIC(19,2)` e `(19,6)`. São as garantias que continuam valendo quando alguém sobe uma segunda instância, roda um script de correção ou introduz um bug na camada de aplicação — ou seja, exatamente a classe de decisão que eu não quero descobrir estar errada depois de ter dados em produção. Código se reescreve numa tarde; esquema com dados dentro, não.

**A ordem das etapas dentro da transação de liquidação.** Idempotência → elegibilidade → cotação congelada → `UPDATE ... WHERE version = ?` + `INSERT`. Foi o defeito central do Anexo A (duas escritas sem transação, com `catch` vazio no meio) e é o ponto onde uma reordenação aparentemente inócua volta como pagamento duplicado.

**As mensagens de commit e a descrição dos PRs.** O histórico é o registro de por que cada coisa está assim, e o enunciado diz que ele será aberto e questionado. Mensagem de commit gerada a partir do diff descreve **o quê** — o diff já faz isso. O **porquê** só existe na cabeça de quem decidiu.

---

## 5. O que eu delegei conscientemente, com a ressalva honesta

Delegar redação de documento é legítimo e eu fiz isso: partes do `REVIEW.md`, do `README.md`, do `ARCHITECTURE.md` e do `DECISIONS.md` foram redigidas a partir de briefs meus, longos e específicos — com a lista dos achados a cobrir, os arquivos do repositório a citar, o tom e a proibição explícita de inventar nome de classe, endpoint, métrica ou número de teste.

A ressalva que eu faço questão de registrar: **o conteúdo das decisões é meu; a redação foi assistida; e cada afirmação verificável foi conferida contra o repositório por mim.** O caso 3.1 é a prova de que essa conferência não é formalidade — foi ela que impediu o `REVIEW.md` de afirmar uma coisa e o lint de fazer outra. Documento e código divergindo é pior do que documento ausente: o leitor confia no primeiro.

O que eu **não** aceito de redação assistida: número que eu não conferi, nome de arquivo que eu não abri e alegação de comportamento que não tem teste. Se algo aqui ou no `README.md` afirma que o sistema faz X, existe um teste ou um arquivo que sustenta.

---

## 6. Como isso se parece no dia a dia de um time

Três coisas deste repositório existem para que o ciclo com IA funcione sem depender da minha atenção:

1. **O linter barra o que a IA mais produz por default**: `catch` vazio, `float`/`double`, construtor de `BigDecimal` com literal numérico. São três defeitos que passam em review humano cansado e que a máquina pega sempre.
2. **Os golden cases rodam na CI**, com valores aferidos externamente. Qualquer refatoração — minha ou assistida — que mude um centavo falha antes do merge.
3. **O teste de concorrência com banco real** (8 liquidações simultâneas do mesmo recebível, exatamente 1 pagamento) é o que nenhuma revisão de diff consegue substituir. É também o teste que um assistente jamais escreveria por iniciativa própria, porque a janela de corrida não está visível no código que ele está lendo.

E funciona nos dois sentidos. Na última fatia, ao ligar o log estruturado, a suíte quebrou num teste que **não tinha relação com a mudança**: cadastro de recebível com vencimento de ontem passou a responder `422` em vez do `400` contratado. A causa era anterior e latente — `@FutureOrPresent` consultava o fuso do sistema, enquanto todo o resto deriva o "hoje" do `Clock` em UTC, e perto da virada do dia em São Paulo as duas visões discordam. Consertei apontando o validador para o mesmo relógio da aplicação, com um teste na fronteira ("vencimento hoje é aceito"). Não foi mérito de revisão nem de IA: foi a suíte de integração existir e ser levada a sério quando falha.

Em resumo: a IA acelerou muito o volume e não mudou nada no que eu tenho de garantir. A verificação continua sendo trabalho meu — e o que eu ganhei de tempo, gastei nela.
