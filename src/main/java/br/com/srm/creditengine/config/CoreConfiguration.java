package br.com.srm.creditengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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

    /**
     * O validador usa o <b>mesmo</b> relogio da aplicacao.
     *
     * <p>Por default, {@code @FutureOrPresent} pergunta a hora ao fuso do sistema, enquanto
     * todo o resto deriva o "hoje" do {@link Clock} em UTC. As duas visoes divergem por
     * algumas horas por dia, e o efeito era concreto: perto da virada do dia, recebivel
     * vencido passava pela validacao de entrada e so era recusado la dentro, trocando o
     * {@code 400} contratado por {@code 422}. Fonte de tempo unica e pre-requisito para
     * sistema com prazo e vigencia - se "hoje" depende de quem pergunta, prazo e cotacao
     * tambem dependem.
     */
    @Bean
    public LocalValidatorFactoryBean defaultValidator(Clock clock) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setConfigurationInitializer(configuration -> configuration.clockProvider(() -> clock));
        return validator;
    }
}
