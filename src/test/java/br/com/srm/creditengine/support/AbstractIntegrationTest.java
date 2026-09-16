package br.com.srm.creditengine.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Set;

/**
 * Base dos testes de integracao: PostgreSQL real via Testcontainers, com as migrations do
 * Flyway aplicadas.
 *
 * <p>Nao usamos H2: as garantias que importam neste sistema (NUMERIC exato, trigger de
 * imutabilidade, comportamento de unicidade sob concorrencia) sao especificas do
 * PostgreSQL. Testar contra outro banco daria uma falsa sensacao de seguranca.
 *
 * <p>Redis tambem e real, pelo mesmo motivo: o guarda de idempotencia depende da semantica
 * de {@code SET NX PX}, de TTL e de script Lua de compare-and-delete. Um fake em memoria
 * provaria que o codigo compila, nao que a reserva funciona sob concorrencia.
 *
 * <p>Os containers sao estaticos e compartilhados entre as classes de teste (singleton
 * container): sobem uma vez por execucao da suite.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection("redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void fixedPricingParameters(DynamicPropertyRegistry registry) {
        // Fixa as premissas dos golden cases para que o teste nao dependa do ambiente.
        registry.add("credit-engine.pricing.base-monthly-rate", () -> "0.01");
        registry.add("credit-engine.pricing.duplicata-monthly-spread", () -> "0.015");
        registry.add("credit-engine.pricing.cheque-monthly-spread", () -> "0.025");
    }

    protected void truncateAll() {
        // settlements tem trigger que bloqueia DELETE; TRUNCATE nao dispara trigger de linha.
        jdbcClient.sql("TRUNCATE TABLE settlements, receivables, fx_rates, assignors CASCADE").update();
        clearIdempotencyKeys();
    }

    /**
     * Limpa as reservas entre testes.
     *
     * <p>Sem isso, uma chave concluida sobreviveria ao {@code TRUNCATE} e o teste seguinte
     * receberia "ja liquidado" para uma tabela vazia - exatamente o estado inconsistente
     * entre guarda e banco que o codigo de producao trata, mas que em teste so produz ruido.
     */
    protected void clearIdempotencyKeys() {
        Set<String> keys = redisTemplate.keys("srm:idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
