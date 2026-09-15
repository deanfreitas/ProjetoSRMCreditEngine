package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.pricing.BaseRateProvider;
import br.com.srm.creditengine.domain.pricing.ChequePreDatadoStrategy;
import br.com.srm.creditengine.domain.pricing.DuplicataMercantilStrategy;
import br.com.srm.creditengine.domain.pricing.FixedBaseRateProvider;
import br.com.srm.creditengine.domain.pricing.PricingEngine;
import br.com.srm.creditengine.domain.pricing.PricingStrategy;
import br.com.srm.creditengine.domain.pricing.PricingStrategyRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Monta o motor de precificacao.
 *
 * <p>O dominio nao tem anotacao de framework: a ligacao com o Spring acontece <b>aqui</b>.
 * Consequencia pratica - os testes do motor e dos golden cases instanciam tudo com
 * {@code new}, sem subir contexto, e rodam em milissegundos.
 */
@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class PricingConfiguration {

    @Bean
    public PricingStrategy duplicataMercantilStrategy(PricingProperties properties) {
        return new DuplicataMercantilStrategy(properties.getDuplicataMonthlySpread());
    }

    @Bean
    public PricingStrategy chequePreDatadoStrategy(PricingProperties properties) {
        return new ChequePreDatadoStrategy(properties.getChequeMonthlySpread());
    }

    @Bean
    public PricingStrategyRegistry pricingStrategyRegistry(List<PricingStrategy> strategies) {
        return new PricingStrategyRegistry(strategies);
    }

    @Bean
    public BaseRateProvider baseRateProvider(PricingProperties properties) {
        return new FixedBaseRateProvider(properties.getBaseMonthlyRate());
    }

    @Bean
    public PricingEngine pricingEngine(PricingStrategyRegistry registry, BaseRateProvider baseRateProvider) {
        return new PricingEngine(registry, baseRateProvider);
    }
}
