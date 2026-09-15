package br.com.srm.creditengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreConfiguration {

    /**
     * Relogio injetado em vez de {@code Instant.now()} espalhado: liquidacao tem timestamp
     * auditavel e teste precisa poder fixar o tempo.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
