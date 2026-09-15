package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.infrastructure.fx.StoredFxRateProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(FxProperties.class)
public class CoreConfiguration {

    /**
     * Relogio injetado em vez de {@code Instant.now()} espalhado: liquidacao tem timestamp
     * auditavel e teste precisa poder fixar o tempo.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FxRateProvider fxRateProvider(FxRateRepository fxRateRepository, FxProperties properties) {
        return new StoredFxRateProvider(fxRateRepository, properties.getMaxStaleness());
    }
}
