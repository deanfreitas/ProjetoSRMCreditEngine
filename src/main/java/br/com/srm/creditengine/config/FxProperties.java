package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Parametros da gestao de cambio. */
@Validated
@ConfigurationProperties(prefix = "credit-engine.fx")
public class FxProperties {

    /**
     * Idade maxima aceitavel da cotacao vigente. Acima disso a liquidacao cross-currency
     * falha com 503 em vez de usar taxa velha.
     */
    @NotNull
    private Duration maxStaleness = Duration.ofHours(12);

    @Valid
    private Upstream upstream = new Upstream();

    @Valid
    private Resilience resilience = new Resilience();

    public Duration getMaxStaleness() {
        return maxStaleness;
    }

    public void setMaxStaleness(Duration maxStaleness) {
        this.maxStaleness = maxStaleness;
    }

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream;
    }

    public Resilience getResilience() {
        return resilience;
    }

    public void setResilience(Resilience resilience) {
        this.resilience = resilience;
    }

    /**
     * Provedor externo de cotacao (aqui, mockado).
     *
     * <p>Nasce <b>desligado</b>: um mock que sobe sozinho e o tipo de coisa que acaba
     * precificando operacao real em producao. O compose de demonstracao liga
     * explicitamente via {@code CREDIT_ENGINE_FX_UPSTREAM_ENABLED}.
     */
    public static class Upstream {

        private boolean enabled = false;

        /**
         * Latencia simulada da chamada. Existe para que o timeout seja testavel: sem
         * dependencia lenta nao ha como provar que o timeout funciona.
         */
        @NotNull
        private Duration latency = Duration.ofMillis(80);

        /**
         * Fracao de chamadas que o mock recusa (0 a 1). Instabilidade controlada para
         * exercitar retry e disjuntor sem depender de rede real.
         */
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private BigDecimal failureRate = BigDecimal.ZERO;

        /**
         * Cotacoes que o mock conhece, indexadas pela moeda base e cotadas na moeda da
         * chave de {@link #quoteCurrency}. Par fora desta tabela e respondido como
         * indisponivel - o mock nao inventa taxa.
         */
        private Map<Currency, BigDecimal> rates = new EnumMap<>(Currency.class);

        /** Moeda de cotacao dos pares acima. */
        @NotNull
        private Currency quoteCurrency = Currency.BRL;

        /** Identificador gravado na auditoria como fonte da taxa. */
        @NotNull
        private String source = "MOCK_UPSTREAM";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getLatency() {
            return latency;
        }

        public void setLatency(Duration latency) {
            this.latency = latency;
        }

        public BigDecimal getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(BigDecimal failureRate) {
            this.failureRate = failureRate;
        }

        public Map<Currency, BigDecimal> getRates() {
            return rates;
        }

        public void setRates(Map<Currency, BigDecimal> rates) {
            this.rates = rates;
        }

        public Currency getQuoteCurrency() {
            return quoteCurrency;
        }

        public void setQuoteCurrency(Currency quoteCurrency) {
            this.quoteCurrency = quoteCurrency;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    /**
     * Guarda-corpo da chamada ao provedor externo: timeout, retry e disjuntor.
     *
     * <p>Os tres existem por motivos diferentes. Timeout impede que a thread da
     * liquidacao fique presa esperando terceiro. Retry cobre a falha de um pacote.
     * Disjuntor impede que, com o terceiro fora do ar, cada requisicao pague o timeout
     * inteiro e a aplicacao caia junto.
     */
    public static class Resilience {

        /** Teto por tentativa, nao pela requisicao inteira. */
        @NotNull
        private Duration callTimeout = Duration.ofMillis(800);

        @Min(1)
        private int maxAttempts = 3;

        @NotNull
        private Duration retryBackoff = Duration.ofMillis(100);

        /** Chamadas consideradas na janela do disjuntor. */
        @Min(2)
        private int slidingWindowSize = 10;

        /** Abaixo disso o disjuntor nao decide nada: amostra pequena engana. */
        @Min(2)
        private int minimumNumberOfCalls = 5;

        /** Percentual de falha que abre o disjuntor. */
        @NotNull
        @DecimalMin("1.0")
        @DecimalMax("100.0")
        private BigDecimal failureRateThreshold = new BigDecimal("50");

        @NotNull
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        @Min(1)
        private int permittedCallsInHalfOpenState = 2;

        /** Threads dedicadas a chamada externa: a liquidacao nao empresta a sua. */
        @Min(1)
        private int maxConcurrentCalls = 8;

        public Duration getCallTimeout() {
            return callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public BigDecimal getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(BigDecimal failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }
    }
}
