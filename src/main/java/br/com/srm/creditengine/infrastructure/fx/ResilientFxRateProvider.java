package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.Currency;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Cotacao com provedor externo protegido por timeout, retry e disjuntor.
 *
 * <p>O caminho rapido e o banco: se existe cotacao vigente e fresca no historico, o
 * terceiro nao e consultado. Liquidacao nao deve depender de rede alheia para funcionar
 * no caso normal - e essa e tambem a razao de o disjuntor aberto <b>nao</b> derrubar a
 * operacao: com taxa fresca em casa, liquida-se normalmente.
 *
 * <p>Quando nao ha taxa utilizavel, busca-se no provedor externo com tres guarda-corpos:
 *
 * <ol>
 *   <li><b>timeout por tentativa</b> - a thread da liquidacao nao fica presa esperando
 *       terceiro. Sem isso, um provedor lento consome o pool de conexoes do banco, porque
 *       cada requisicao segura a transacao aberta enquanto espera;</li>
 *   <li><b>retry com backoff</b> - falha de um pacote nao vira erro de negocio. Retry
 *       aqui e seguro por ser uma consulta: nao muda estado de ninguem;</li>
 *   <li><b>disjuntor</b> - com o terceiro fora do ar, para de tentar e falha rapido.
 *       Sem ele, cada requisicao pagaria o timeout inteiro e a fila de entrada da
 *       aplicacao estouraria por causa de um sistema que nem e nosso.</li>
 * </ol>
 *
 * <p>Ordem da composicao: disjuntor por fora, retry no meio, timeout por dentro. Assim
 * uma requisicao conta como <b>uma</b> observacao no disjuntor (nao tres, o que abriria o
 * circuito em uma unica requisicao ruim) e o teto de tempo vale por tentativa.
 *
 * <p>O que este componente <b>nao</b> faz, de proposito: nao devolve cotacao defasada
 * como plano B. Taxa velha em dia de volatilidade nao e degradacao graciosa, e prejuizo
 * silencioso - a liquidacao falha com 503 e o operador decide. Tambem nao aceita de olhos
 * fechados o que o terceiro responde: par trocado ou vigencia no futuro sao recusados.
 */
public class ResilientFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientFxRateProvider.class);

    private static final String LOOKUP_METRIC = "credit_engine.fx.lookups";

    /**
     * Folga aceita entre o nosso relogio e o do provedor. A vigencia que o terceiro
     * devolve e naturalmente alguns milissegundos posterior ao instante em que a consulta
     * comecou, e os dois relogios nao estao sincronizados ao milissegundo. Sem essa
     * folga, cotacao boa seria recusada; com folga grande, taxa agendada para o futuro
     * entraria como se fosse de agora.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(5);

    private final FxRateProvider storedRates;
    private final ExternalFxRateSource upstream;
    private final FxRateWriter fxRateWriter;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration maxStaleness;

    public ResilientFxRateProvider(FxRateProvider storedRates,
                                   ExternalFxRateSource upstream,
                                   FxRateWriter fxRateWriter,
                                   CircuitBreaker circuitBreaker,
                                   Retry retry,
                                   TimeLimiter timeLimiter,
                                   ExecutorService executor,
                                   MeterRegistry meterRegistry,
                                   Clock clock,
                                   Duration maxStaleness) {
        this.storedRates = storedRates;
        this.upstream = upstream;
        this.fxRateWriter = fxRateWriter;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.maxStaleness = maxStaleness;
    }

    @Override
    public FxRate rateFor(Currency baseCurrency, Currency quoteCurrency, Instant at) {
        try {
            FxRate stored = storedRates.rateFor(baseCurrency, quoteCurrency, at);
            count("stored");
            return stored;
        } catch (FxRateUnavailableException missingOrStale) {
            log.info("Cotacao {}/{} indisponivel no historico ({}); consultando provedor externo",
                    baseCurrency, quoteCurrency, missingOrStale.getMessage());
            return refresh(baseCurrency, quoteCurrency, at, missingOrStale);
        }
    }

    private FxRate refresh(Currency baseCurrency,
                           Currency quoteCurrency,
                           Instant at,
                           FxRateUnavailableException missingOrStale) {
        Optional<FxRate> fetched;
        try {
            fetched = guardedFetch(baseCurrency, quoteCurrency);
        } catch (CallNotPermittedException circuitOpen) {
            // Disjuntor aberto: falha rapida e explicita. Note que nao caimos na cotacao
            // defasada que acabou de ser recusada - degradar valor de liquidacao nao e
            // opcao aceitavel.
            count("circuit_open");
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "provedor externo em disjuntor aberto e sem cotacao fresca em casa");
        } catch (RejectedExecutionException saturated) {
            // Pool de chamadas externas saturado. Recusar e melhor que enfileirar: fila
            // sem limite e como se transforma lentidao de terceiro em queda propria.
            count("rejected");
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "sem capacidade para consultar o provedor externo agora");
        } catch (Exception failure) {
            // Captura ampla no limite com o mundo externo: aqui chegam TimeoutException
            // (checada, do TimeLimiter) e a falha tecnica do provedor. Nao e excecao
            // engolida - a causa vai no log, no contador e no encadeamento da excecao de
            // dominio, que a API traduz em 503.
            count("upstream_failed");
            log.warn("Falha ao consultar o provedor externo de cotacao {}/{}: {}",
                    baseCurrency, quoteCurrency, failure.toString());
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency, failure);
        }

        if (fetched.isEmpty()) {
            // O terceiro respondeu que nao cota o par. Nao ha o que retentar; o erro
            // original (sem cotacao em casa) continua sendo a melhor explicacao.
            count("pair_unknown");
            throw missingOrStale;
        }

        FxRate rate = validate(fetched.get(), baseCurrency, quoteCurrency, at);
        FxRate persisted = fxRateWriter.store(rate);
        count("refreshed");
        log.info("Cotacao {}/{} atualizada pelo provedor externo: rate={} effectiveAt={} source={}",
                persisted.baseCurrency(), persisted.quoteCurrency(), persisted.rate(),
                persisted.effectiveAt(), persisted.source());
        return persisted;
    }

    private Optional<FxRate> guardedFetch(Currency baseCurrency, Currency quoteCurrency) throws Exception {
        Supplier<CompletableFuture<Optional<FxRate>>> call =
                () -> CompletableFuture.supplyAsync(() -> upstream.fetch(baseCurrency, quoteCurrency), executor);

        Callable<Optional<FxRate>> withTimeout = TimeLimiter.decorateFutureSupplier(timeLimiter, call);
        Callable<Optional<FxRate>> withRetry = Retry.decorateCallable(retry, withTimeout);
        Callable<Optional<FxRate>> guarded = CircuitBreaker.decorateCallable(circuitBreaker, withRetry);

        return guarded.call();
    }

    /**
     * O provedor externo e uma fonte nao confiavel: o que ele responde e verificado antes
     * de virar base de calculo de pagamento.
     */
    private FxRate validate(FxRate rate, Currency baseCurrency, Currency quoteCurrency, Instant at) {
        if (rate.baseCurrency() != baseCurrency || rate.quoteCurrency() != quoteCurrency) {
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "provedor externo respondeu o par %s/%s".formatted(rate.baseCurrency(), rate.quoteCurrency()));
        }
        Instant observedAt = clock.instant();
        if (rate.effectiveAt().isAfter(observedAt.plus(CLOCK_SKEW_TOLERANCE))) {
            // Taxa com vigencia no futuro nao vale para liquidar hoje: seria precificar
            // com um numero que ainda nao entrou em vigor.
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "provedor externo respondeu vigencia futura (%s > %s)"
                            .formatted(rate.effectiveAt(), observedAt));
        }
        Duration age = Duration.between(rate.effectiveAt(), at);
        if (age.compareTo(maxStaleness) > 0) {
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "provedor externo respondeu cotacao defasada (%s > %s)".formatted(age, maxStaleness));
        }
        return rate;
    }

    private void count(String outcome) {
        meterRegistry.counter(LOOKUP_METRIC, "outcome", outcome).increment();
    }
}
