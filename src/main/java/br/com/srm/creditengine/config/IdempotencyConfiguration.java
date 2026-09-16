package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.settlement.IdempotencyStore;
import br.com.srm.creditengine.infrastructure.idempotency.DisabledIdempotencyStore;
import br.com.srm.creditengine.infrastructure.idempotency.RedisIdempotencyStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Montagem do guarda de idempotencia.
 *
 * <p>Ha duas implementacoes, e a escolha e de configuracao e nao de codigo: com Redis
 * ligado a chave e reservada na borda, antes do trabalho caro; desligado, a decisao volta
 * para dentro da transacao, no {@code SELECT} em {@code settlements}. Os dois caminhos sao
 * corretos - o segundo apenas paga mais conexao de pool e deixa duas requisicoes
 * simultaneas resolverem cotacao para uma delas perder no {@code INSERT}.
 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "credit-engine.idempotency", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate,
                                                  MeterRegistry meterRegistry,
                                                  IdempotencyProperties properties) {
        log.info("Guarda de idempotencia em Redis ativo: prefixo={} ttlEmProcessamento={} ttlConcluido={}",
                properties.getKeyPrefix(), properties.getInProgressTtl(), properties.getCompletedTtl());
        return new RedisIdempotencyStore(
                redisTemplate,
                meterRegistry,
                properties.getKeyPrefix(),
                properties.getInProgressTtl(),
                properties.getCompletedTtl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "credit-engine.idempotency", name = "enabled", havingValue = "false")
    public IdempotencyStore disabledIdempotencyStore() {
        log.info("Guarda de idempotencia desligado: a decisao fica no PostgreSQL, dentro da transacao da liquidacao");
        return new DisabledIdempotencyStore();
    }
}
