package br.com.srm.creditengine.infrastructure.fx;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Estado do provedor externo de cotacao no {@code /actuator/health}.
 *
 * <p>Disjuntor aberto e degradacao, nao queda: liquidacao em moeda unica e liquidacao
 * cross-currency com cotacao fresca no historico continuam funcionando. Por isso o status
 * e {@code DEGRADED} e nao {@code DOWN} - marcar a aplicacao como fora do ar faria o
 * orquestrador reiniciar ou tirar do balanceador uma instancia saudavel, transformando
 * problema de terceiro em indisponibilidade propria.
 */
public class FxUpstreamHealthIndicator implements HealthIndicator {

    private static final Status DEGRADED = new Status("DEGRADED", "Provedor externo de cotacao indisponivel");

    private final CircuitBreaker circuitBreaker;

    public FxUpstreamHealthIndicator(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = circuitBreaker.getState();
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        Health.Builder builder = state == CircuitBreaker.State.OPEN
                ? Health.status(DEGRADED)
                : Health.up();

        return builder
                .withDetail("circuitBreaker", circuitBreaker.getName())
                .withDetail("state", state.name())
                .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
                .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
                .build();
    }
}
