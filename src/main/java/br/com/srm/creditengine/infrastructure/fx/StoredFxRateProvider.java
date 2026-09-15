package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.Currency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Cotacao vinda do historico gravado no banco (alimentado por atualizacao manual ou pelo
 * provedor externo mockado).
 *
 * <p>Alem de existir, a cotacao precisa estar <b>fresca</b>: uma taxa de tres dias atras
 * nao serve para liquidar hoje. Cotacao vencida e tratada como indisponibilidade
 * ({@code 503}), nunca usada em silencio.
 */
public class StoredFxRateProvider implements FxRateProvider {

    private final FxRateRepository fxRateRepository;
    private final Duration maxStaleness;

    public StoredFxRateProvider(FxRateRepository fxRateRepository, Duration maxStaleness) {
        this.fxRateRepository = fxRateRepository;
        this.maxStaleness = maxStaleness;
    }

    @Override
    public FxRate rateFor(Currency baseCurrency, Currency quoteCurrency, Instant at) {
        Optional<FxRate> rate = fxRateRepository.findLatest(baseCurrency, quoteCurrency, at);

        if (rate.isEmpty()) {
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "sem cotacao vigente em " + at);
        }

        FxRate found = rate.get();
        Duration age = Duration.between(found.effectiveAt(), at);
        if (age.compareTo(maxStaleness) > 0) {
            throw new FxRateUnavailableException(baseCurrency, quoteCurrency,
                    "cotacao vigente desde %s esta defasada (%s > %s)"
                            .formatted(found.effectiveAt(), age, maxStaleness));
        }

        return found;
    }
}
