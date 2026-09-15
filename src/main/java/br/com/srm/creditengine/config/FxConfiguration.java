package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.infrastructure.fx.ExternalFxRateSource;
import br.com.srm.creditengine.infrastructure.fx.FxRateSourceException;
import br.com.srm.creditengine.infrastructure.fx.FxUpstreamHealthIndicator;
import br.com.srm.creditengine.infrastructure.fx.MockExternalFxRateSource;
import br.com.srm.creditengine.infrastructure.fx.ResilientFxRateProvider;
import br.com.srm.creditengine.infrastructure.fx.StoredFxRateProvider;
import br.com.srm.creditengine.infrastructure.fx.TransactionalFxRateWriter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Montagem do Currency Engine.
 *
 * <p>A resiliencia e composta aqui, de forma programatica, em vez de anotada nos metodos.
 * O motivo e poder explicar (e testar) a ordem dos guarda-corpos: com anotacao, a ordem
 * dos aspectos fica implicita no framework, e "por que o disjuntor abriu em uma unica
 * requisicao" vira arqueologia.
 */
@Configuration
@EnableConfigurationProperties(FxProperties.class)
public class FxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FxConfiguration.class);

    /** Nome do disjuntor nas metricas e no health: aparece assim no dashboard. */
    private static final String CIRCUIT_BREAKER_NAME = "fx-upstream";

    @Bean
    public StoredFxRateProvider storedFxRateProvider(FxRateRepository fxRateRepository, FxProperties properties) {
        return new StoredFxRateProvider(fxRateRepository, properties.getMaxStaleness());
    }

    /**
     * Provedor externo mockado, criado somente quando ligado por configuracao. Sem ele, o
     * sistema opera apenas com o historico de cotacoes alimentado pela rota manual.
     */
    @Bean
    @ConditionalOnProperty(prefix = "credit-engine.fx.upstream", name = "enabled", havingValue = "true")
    public ExternalFxRateSource externalFxRateSource(FxProperties properties, Clock clock) {
        return new MockExternalFxRateSource(properties.getUpstream(), clock);
    }

    @Bean
    public TransactionalFxRateWriter fxRateWriter(FxRateRepository fxRateRepository,
                                                  PlatformTransactionManager transactionManager) {
        return new TransactionalFxRateWriter(fxRateRepository, transactionManager);
    }

    @Bean
    public CircuitBreakerRegistry fxCircuitBreakerRegistry(FxProperties properties, MeterRegistry meterRegistry) {
        FxProperties.Resilience resilience = properties.getResilience();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.getSlidingWindowSize())
                .minimumNumberOfCalls(resilience.getMinimumNumberOfCalls())
                // resilience4j exige float neste limiar. E percentual de falha, nao
                // dinheiro: precisao binaria aqui nao custa centavo.
                .failureRateThreshold(resilience.getFailureRateThreshold().floatValue())
                .waitDurationInOpenState(resilience.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(resilience.getPermittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Saturacao do nosso pool nao e falha do terceiro: contar isso abriria o
                // disjuntor por causa de um problema de capacidade nossa.
                .ignoreExceptions(RejectedExecutionException.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        // Estado e taxa de falha do disjuntor viram metrica: em incidente, a primeira
        // pergunta e "o disjuntor esta aberto?".
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker fxCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Disjuntor do cambio mudou de estado: {}", event.getStateTransition()));
        return circuitBreaker;
    }

    @Bean
    public Retry fxRetry(FxProperties properties) {
        FxProperties.Resilience resilience = properties.getResilience();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(resilience.getMaxAttempts())
                .waitDuration(resilience.getRetryBackoff())
                // Consultar cotacao e leitura: repetir nao duplica pagamento. O retry da
                // liquidacao, que muda estado, e responsabilidade do cliente com
                // Idempotency-Key - nao acontece aqui dentro.
                .retryExceptions(FxRateSourceException.class, TimeoutException.class)
                .ignoreExceptions(RejectedExecutionException.class)
                .build();
        return Retry.of("fx-upstream", config);
    }

    @Bean
    public TimeLimiter fxTimeLimiter(FxProperties properties) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(properties.getResilience().getCallTimeout())
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of("fx-upstream", config);
    }

    /**
     * Pool dedicado a chamada externa. Pequeno e sem fila (fila sincrona): quando o
     * terceiro fica lento, a rejeicao aparece rapido em vez de acumular requisicoes
     * esperando - e uma aplicacao que espera em silencio e uma aplicacao que vai cair.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService fxUpstreamExecutor(FxProperties properties) {
        return new ThreadPoolExecutor(
                1,
                properties.getResilience().getMaxConcurrentCalls(),
                30L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "fx-upstream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Provedor usado por toda a aplicacao. Com o provedor externo desligado, e apenas o
     * historico do banco; com ele ligado, o historico continua sendo o caminho rapido e a
     * consulta externa entra so quando nao ha cotacao utilizavel em casa.
     */
    @Bean
    @Primary
    public FxRateProvider fxRateProvider(StoredFxRateProvider storedFxRateProvider,
                                         TransactionalFxRateWriter fxRateWriter,
                                         CircuitBreaker fxCircuitBreaker,
                                         Retry fxRetry,
                                         TimeLimiter fxTimeLimiter,
                                         ExecutorService fxUpstreamExecutor,
                                         MeterRegistry meterRegistry,
                                         Clock clock,
                                         FxProperties properties,
                                         ObjectProvider<ExternalFxRateSource> upstream) {
        ExternalFxRateSource source = upstream.getIfAvailable();
        if (source == null) {
            log.info("Provedor externo de cotacao desligado: somente cotacoes registradas manualmente");
            return storedFxRateProvider;
        }
        return new ResilientFxRateProvider(
                storedFxRateProvider,
                source,
                fxRateWriter,
                fxCircuitBreaker,
                fxRetry,
                fxTimeLimiter,
                fxUpstreamExecutor,
                meterRegistry,
                clock,
                properties.getMaxStaleness());
    }

    @Bean
    @ConditionalOnProperty(prefix = "credit-engine.fx.upstream", name = "enabled", havingValue = "true")
    public HealthIndicator fxUpstreamHealthIndicator(CircuitBreaker fxCircuitBreaker) {
        return new FxUpstreamHealthIndicator(fxCircuitBreaker);
    }
}
