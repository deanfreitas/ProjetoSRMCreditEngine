package br.com.srm.creditengine.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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

    public Duration getMaxStaleness() {
        return maxStaleness;
    }

    public void setMaxStaleness(Duration maxStaleness) {
        this.maxStaleness = maxStaleness;
    }
}
