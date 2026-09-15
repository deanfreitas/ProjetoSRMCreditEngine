package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.Currency;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resiliencia do cambio sem Spring e sem banco: o que esta sob teste aqui e o
 * <b>comportamento sob falha do terceiro</b>, e ele precisa ser verificavel em
 * milissegundos, nao esperando 30 segundos de disjuntor.
 *
 * <p>A pergunta que cada teste responde e sempre a mesma em linguagem de negocio: com o
 * provedor de cotacao instavel, o sistema paga errado, trava, ou recusa a operacao de
 * forma explicita? Somente a terceira resposta e aceitavel.
 */
class ResilientFxRateProviderTest {

    private static final Instant NOW = Instant.parse("2025-01-15T12:00:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<FxRate> stored = new ArrayList<>();

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        // Pool com folga: o teste de timeout deixa threads ocupadas de proposito, e nao
        // queremos que isso contamine os testes seguintes.
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static FxRate usdBrl(String rate, Instant effectiveAt, String source) {
        return FxRate.of(Currency.USD, Currency.BRL, rate, effectiveAt, source);
    }

    /** Historico vazio: qualquer consulta cai no provedor externo. */
    private static FxRateProvider emptyHistory() {
        return (base, quote, at) -> {
            throw new FxRateUnavailableException(base, quote, "sem cotacao vigente em " + at);
        };
    }

    private ResilientFxRateProvider provider(FxRateProvider history,
                                             ExternalFxRateSource upstream,
                                             CircuitBreaker circuitBreaker,
                                             Retry retry,
                                             TimeLimiter timeLimiter) {
        return new ResilientFxRateProvider(
                history,
                upstream,
                rate -> {
                    stored.add(rate);
                    return rate;
                },
                circuitBreaker,
                retry,
                timeLimiter,
                executor,
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(12));
    }

    private static CircuitBreaker circuitBreaker() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreExceptions(RejectedExecutionException.class)
                .build());
    }

    private static Retry retry(int maxAttempts) {
        return Retry.of("test", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(FxRateSourceException.class, java.util.concurrent.TimeoutException.class)
                .build());
    }

    private static TimeLimiter timeLimiter(Duration timeout) {
        return TimeLimiter.of("test", TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
    }

    /** Provedor externo instrumentado: conta chamadas e responde o que o teste mandar. */
    private static final class CountingSource implements ExternalFxRateSource {

        private final AtomicInteger calls = new AtomicInteger();
        private final Supplier<Optional<FxRate>> behaviour;

        private CountingSource(Supplier<Optional<FxRate>> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Optional<FxRate> fetch(Currency baseCurrency, Currency quoteCurrency) {
            calls.incrementAndGet();
            return behaviour.get();
        }

        int calls() {
            return calls.get();
        }
    }

    @Test
    @DisplayName("Com cotacao fresca no historico, o provedor externo nao e consultado")
    void fastPathSkipsUpstream() {
        FxRate fresh = usdBrl("5.4321", NOW.minusSeconds(60), "MANUAL");
        CountingSource upstream = new CountingSource(Optional::empty);

        FxRate rate = provider((base, quote, at) -> fresh, upstream,
                circuitBreaker(), retry(3), timeLimiter(Duration.ofMillis(200)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(fresh);
        assertThat(upstream.calls()).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Sem cotacao em casa, busca no provedor externo e grava no historico")
    void refreshesFromUpstream() {
        FxRate upstreamRate = usdBrl("5.4321", NOW.minusSeconds(5), "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(upstreamRate));

        FxRate rate = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(3), timeLimiter(Duration.ofMillis(500)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(upstreamRate);
        assertThat(upstream.calls()).isOne();
        // Gravar e o que torna a liquidacao auditavel depois e evita bater no terceiro
        // na proxima requisicao.
        assertThat(stored).containsExactly(upstreamRate);
    }

    @Test
    @DisplayName("Provedor externo lento estoura o timeout e a liquidacao e recusada, nao fica presa")
    void timeoutFailsFast() {
        CountingSource upstream = new CountingSource(() -> {
            sleep(Duration.ofMillis(600));
            return Optional.of(usdBrl("5.4321", NOW, "MOCK_UPSTREAM"));
        });

        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(1), timeLimiter(Duration.ofMillis(60)));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // O ponto do timeout: a espera termina em fracao do tempo do terceiro.
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Falha passageira e coberta pelo retry: a segunda tentativa resolve")
    void retriesTransientFailure() {
        FxRate upstreamRate = usdBrl("5.4321", NOW.minusSeconds(5), "MOCK_UPSTREAM");
        AtomicInteger attempt = new AtomicInteger();
        CountingSource upstream = new CountingSource(() -> {
            if (attempt.incrementAndGet() == 1) {
                throw new FxRateSourceException("conexao reiniciada pelo par");
            }
            return Optional.of(upstreamRate);
        });

        FxRate rate = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(3), timeLimiter(Duration.ofMillis(500)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(upstreamRate);
        assertThat(upstream.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("Provedor fora do ar abre o disjuntor e as requisicoes seguintes falham sem bater nele")
    void opensCircuitAndStopsCallingUpstream() {
        CountingSource upstream = new CountingSource(() -> {
            throw new FxRateSourceException("provedor fora do ar");
        });
        CircuitBreaker circuitBreaker = circuitBreaker();
        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker, retry(1), timeLimiter(Duration.ofMillis(200)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                    .isInstanceOf(FxRateUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeOpen = upstream.calls();
        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("disjuntor aberto");

        // O valor do disjuntor: com o terceiro fora do ar, paramos de pagar timeout por
        // requisicao. Sem isso, a fila de entrada da aplicacao estoura por causa dele.
        assertThat(upstream.calls()).isEqualTo(callsBeforeOpen);
    }

    @Test
    @DisplayName("Disjuntor aberto nao impede liquidar quando existe cotacao fresca em casa")
    void openCircuitDoesNotBlockSettlementWithFreshRate() {
        FxRate fresh = usdBrl("5.4321", NOW.minusSeconds(30), "MANUAL");
        CountingSource upstream = new CountingSource(() -> {
            throw new FxRateSourceException("provedor fora do ar");
        });
        CircuitBreaker circuitBreaker = circuitBreaker();
        circuitBreaker.transitionToOpenState();

        FxRate rate = provider((base, quote, at) -> fresh, upstream,
                circuitBreaker, retry(1), timeLimiter(Duration.ofMillis(200)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(fresh);
        assertThat(upstream.calls()).isZero();
    }

    @Test
    @DisplayName("Depois da janela de espera, uma chamada bem-sucedida fecha o disjuntor")
    void recoversWhenUpstreamComesBack() {
        FxRate upstreamRate = usdBrl("5.4321", NOW.minusSeconds(5), "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(upstreamRate));
        CircuitBreaker circuitBreaker = circuitBreaker();
        circuitBreaker.transitionToOpenState();
        // Em producao essa transicao e automatica depois de wait-duration-in-open-state;
        // aqui e forcada para o teste nao depender de tempo de parede.
        circuitBreaker.transitionToHalfOpenState();

        FxRate rate = provider(emptyHistory(), upstream,
                circuitBreaker, retry(1), timeLimiter(Duration.ofMillis(500)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(upstreamRate);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Par que o provedor nao cota nao gera retry nem abre disjuntor")
    void unknownPairIsNotTreatedAsOutage() {
        CountingSource upstream = new CountingSource(Optional::empty);
        CircuitBreaker circuitBreaker = circuitBreaker();

        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker, retry(3), timeLimiter(Duration.ofMillis(200)));

        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("sem cotacao vigente");

        assertThat(upstream.calls()).isOne();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Cotacao do par trocado vinda do provedor e recusada, nao convertida na direcao errada")
    void rejectsRateForAnotherPair() {
        FxRate inverted = FxRate.of(Currency.BRL, Currency.USD, "0.184", NOW, "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(inverted));

        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(1), timeLimiter(Duration.ofMillis(200)));

        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("respondeu o par BRL/USD");

        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Vigencia poucos segundos a frente e aceita: e diferenca de relogio, nao taxa futura")
    void toleratesSmallClockSkew() {
        FxRate slightlyAhead = usdBrl("5.4321", NOW.plusSeconds(1), "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(slightlyAhead));

        FxRate rate = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(1), timeLimiter(Duration.ofMillis(200)))
                .rateFor(Currency.USD, Currency.BRL, NOW);

        assertThat(rate).isEqualTo(slightlyAhead);
        assertThat(stored).containsExactly(slightlyAhead);
    }

    @Test
    @DisplayName("Cotacao com vigencia no futuro vinda do provedor e recusada")
    void rejectsFutureEffectiveAt() {
        FxRate future = usdBrl("5.4321", NOW.plusSeconds(3600), "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(future));

        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(1), timeLimiter(Duration.ofMillis(200)));

        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("vigencia futura");

        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Cotacao defasada vinda do provedor e recusada em vez de usada como plano B")
    void rejectsStaleRateFromUpstream() {
        FxRate stale = usdBrl("5.4321", NOW.minus(Duration.ofHours(30)), "MOCK_UPSTREAM");
        CountingSource upstream = new CountingSource(() -> Optional.of(stale));

        ResilientFxRateProvider provider = provider(emptyHistory(), upstream,
                circuitBreaker(), retry(1), timeLimiter(Duration.ofMillis(200)));

        assertThatThrownBy(() -> provider.rateFor(Currency.USD, Currency.BRL, NOW))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("defasada");

        assertThat(stored).isEmpty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FxRateSourceException("interrompido", e);
        }
    }
}
