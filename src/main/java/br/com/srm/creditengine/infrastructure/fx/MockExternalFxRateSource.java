package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.config.FxProperties;
import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provedor externo mockado.
 *
 * <p>Nao ha integracao real com PTAX no escopo deste desafio, mas o que interessa para o
 * desenho nao e o numero que o terceiro devolve: e como o sistema se comporta quando ele
 * demora ou cai. Por isso o mock simula <b>latencia</b> e <b>instabilidade</b>
 * configuraveis, e nao mais que isso.
 *
 * <p>Duas coisas que ele deliberadamente <b>nao</b> faz:
 *
 * <ul>
 *   <li>nao inventa cotacao de par desconhecido - responde "nao cato esse par", que e
 *       diferente de "estou fora do ar";</li>
 *   <li>nao inverte a cotacao por conta propria. A tabela e explicita (base -> quantas
 *       unidades da moeda de cotacao). Inverter taxa em silencio foi um dos defeitos do
 *       Anexo A.</li>
 * </ul>
 */
public class MockExternalFxRateSource implements ExternalFxRateSource {

    private static final Logger log = LoggerFactory.getLogger(MockExternalFxRateSource.class);

    private final FxProperties.Upstream properties;
    private final Clock clock;

    public MockExternalFxRateSource(FxProperties.Upstream properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<FxRate> fetch(Currency baseCurrency, Currency quoteCurrency) {
        simulateLatency();
        simulateInstability(baseCurrency, quoteCurrency);

        if (quoteCurrency != properties.getQuoteCurrency()) {
            return Optional.empty();
        }

        BigDecimal rate = properties.getRates().get(baseCurrency);
        if (rate == null) {
            return Optional.empty();
        }

        FxRate fetched = new FxRate(
                baseCurrency,
                quoteCurrency,
                rate,
                // A vigencia e o instante da consulta: o mock representa "cotacao de
                // agora". Provedor real traria a vigencia dele, e ela seria respeitada.
                clock.instant(),
                properties.getSource());

        log.debug("Cotacao obtida no provedor externo: pair={}/{} rate={} source={}",
                fetched.baseCurrency(), fetched.quoteCurrency(), fetched.rate(), fetched.source());
        return Optional.of(fetched);
    }

    private void simulateLatency() {
        long millis = properties.getLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Interrupcao aqui e o timeout do TimeLimiter cancelando a chamada. Repor a
            // flag e abortar: engolir a interrupcao deixaria a thread do pool zumbi.
            Thread.currentThread().interrupt();
            throw new FxRateSourceException("Consulta ao provedor externo interrompida", e);
        }
    }

    private void simulateInstability(Currency baseCurrency, Currency quoteCurrency) {
        int failureChanceInPercent = properties.getFailureRate()
                .multiply(BigDecimal.valueOf(100))
                .intValue();
        if (failureChanceInPercent <= 0) {
            return;
        }
        if (ThreadLocalRandom.current().nextInt(100) < failureChanceInPercent) {
            throw new FxRateSourceException(
                    "Provedor externo indisponivel para %s/%s (instabilidade simulada)"
                            .formatted(baseCurrency, quoteCurrency));
        }
    }
}
