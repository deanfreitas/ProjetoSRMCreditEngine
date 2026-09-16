package br.com.srm.creditengine.config;

import br.com.srm.creditengine.api.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ObservabilityConfiguration {

    /**
     * Registra o filtro de correlacao explicitamente, na primeira posicao da cadeia.
     *
     * <p>A ordem nao e detalhe: qualquer coisa que logue antes dele - inclusive o proprio
     * tratamento de erro de requisicao malformada - sairia sem {@code correlationId}, e
     * justamente a requisicao que falhou e a que alguem vai querer rastrear. Registrar por
     * bean em vez de {@code @Component} deixa essa ordem escrita, e nao dependente da
     * ordem de descoberta de componente.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
