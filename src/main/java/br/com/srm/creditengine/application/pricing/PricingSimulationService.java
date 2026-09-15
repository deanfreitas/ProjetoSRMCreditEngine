package br.com.srm.creditengine.application.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.pricing.PricingEngine;
import br.com.srm.creditengine.domain.pricing.PricingInput;
import br.com.srm.creditengine.domain.pricing.PricingResult;
import br.com.srm.creditengine.domain.pricing.TermCalculator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Caso de uso de simulacao: resolve prazo e cotacao e delega ao motor.
 *
 * <p>Nao e transacional e nao grava nada - simulacao nao e operacao contabil. A cotacao,
 * porem, vem do <b>mesmo</b> provedor da liquidacao, com a mesma regra de vigencia e
 * defasagem: se o operador ve na tela um numero calculado com taxa defasada, ele fecha a
 * operacao com um numero que a liquidacao vai recusar.
 */
@Service
public class PricingSimulationService {

    private final PricingEngine pricingEngine;
    private final FxRateProvider fxRateProvider;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public PricingSimulationService(PricingEngine pricingEngine,
                                    FxRateProvider fxRateProvider,
                                    Clock clock,
                                    MeterRegistry meterRegistry) {
        this.pricingEngine = pricingEngine;
        this.fxRateProvider = fxRateProvider;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public PricingResult simulate(SimulatePricingCommand command) {
        Instant now = clock.instant();
        LocalDate referenceDate = LocalDate.ofInstant(now, clock.getZone());
        int termMonths = TermCalculator.termInMonths(referenceDate, command.dueDate());

        PricingInput input = command.settlementCurrency() == command.faceCurrency()
                ? PricingInput.domestic(command.faceMoney(), command.type(), termMonths, referenceDate)
                : crossCurrencyInput(command, termMonths, referenceDate, now);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return pricingEngine.price(input);
        } finally {
            sample.stop(meterRegistry.timer("credit_engine.pricing.duration"));
        }
    }

    private PricingInput crossCurrencyInput(SimulatePricingCommand command,
                                            int termMonths,
                                            LocalDate referenceDate,
                                            Instant now) {
        FxRate fxRate = fxRateProvider.rateFor(command.settlementCurrency(), command.faceCurrency(), now);
        return PricingInput.crossCurrency(
                command.faceMoney(),
                command.type(),
                termMonths,
                command.settlementCurrency(),
                fxRate,
                referenceDate);
    }
}
