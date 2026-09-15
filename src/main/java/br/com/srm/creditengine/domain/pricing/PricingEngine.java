package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Servico de dominio que orquestra a precificacao.
 *
 * <p>Ordem do calculo (premissa dos golden cases, registrada no SPEC.md):
 * <ol>
 *   <li>strategy calcula o valor presente na moeda de face com precisao plena;</li>
 *   <li>arredonda para 2 casas com half-even - <b>unico</b> arredondamento do fluxo em BRL;</li>
 *   <li>desagio = face menos valor presente <b>ja arredondado</b> (fecha a conta ao centavo);</li>
 *   <li>se houver cross-currency, converte o valor presente arredondado e arredonda o
 *       resultado na moeda de destino.</li>
 * </ol>
 *
 * <p>Sem dependencia de framework, banco, relogio ou HTTP: o motor e puro e deterministico.
 * Toda a resolucao de cotacao e data vem pronta em {@link PricingInput}.
 */
public class PricingEngine {

    private final PricingStrategyRegistry strategies;
    private final BaseRateProvider baseRateProvider;

    public PricingEngine(PricingStrategyRegistry strategies, BaseRateProvider baseRateProvider) {
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.baseRateProvider = Objects.requireNonNull(baseRateProvider, "baseRateProvider");
    }

    public PricingResult price(PricingInput input) {
        Objects.requireNonNull(input, "input");

        PricingStrategy strategy = strategies.strategyFor(input.receivableType());
        BigDecimal monthlyBaseRate = baseRateProvider.monthlyBaseRate(input.referenceDate());

        Money presentValue = strategy.unroundedPresentValue(input, monthlyBaseRate).rounded();
        Money faceValue = input.faceValue().rounded();
        Money discount = faceValue.subtract(presentValue).rounded();
        Money settlementAmount = toSettlementCurrency(input, presentValue);

        return new PricingResult(
                faceValue,
                presentValue,
                discount,
                settlementAmount,
                input.termMonths(),
                monthlyBaseRate,
                strategy.monthlySpread(),
                input.fxRate()
        );
    }

    private Money toSettlementCurrency(PricingInput input, Money presentValue) {
        if (!input.isCrossCurrency()) {
            return presentValue;
        }
        FxRate fxRate = input.optionalFxRate()
                .orElseThrow(() -> new MissingFxRateException(
                        input.faceValue().currency(), input.settlementCurrency()));

        return fxRate.convert(presentValue, input.settlementCurrency()).rounded();
    }
}
