package br.com.srm.creditengine.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dos testes de integracao: PostgreSQL real via Testcontainers, com as migrations do
 * Flyway aplicadas.
 *
 * <p>Nao usamos H2: as garantias que importam neste sistema (NUMERIC exato, trigger de
 * imutabilidade, comportamento de unicidade sob concorrencia) sao especificas do
 * PostgreSQL. Testar contra outro banco daria uma falsa sensacao de seguranca.
 *
 * <p>O container e estatico e compartilhado entre as classes de teste (singleton
 * container): sobe uma vez por execucao da suite.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbcClient;

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
    }
}
