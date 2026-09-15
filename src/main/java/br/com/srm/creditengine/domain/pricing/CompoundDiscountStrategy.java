package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.money.RoundingPolicy;

import java.math.BigDecimal;

/**
 * Base dos tipos precificados por desconto composto mensal:
 *
 * <pre>VP = Face / (1 + taxaBase + spread) ^ prazoEmMeses</pre>
 *
 * <p>Subclasse informa apenas tipo e spread. Um tipo que precise de outra formula
 * (desconto simples, carencia, taxa por faixa) implementa {@link PricingStrategy}
 * diretamente, sem herdar daqui.
 */
public abstract class CompoundDiscountStrategy implements PricingStrategy {

    @Override
    public Money unroundedPresentValue(PricingInput input, BigDecimal monthlyBaseRate) {
        BigDecimal effectiveRate = effectiveMonthlyRate(monthlyBaseRate);
        BigDecimal discountFactor = BigDecimal.ONE
                .add(effectiveRate)
                .pow(input.termMonths(), RoundingPolicy.CALCULATION);

        BigDecimal presentValue = input.faceValue().amount()
                .divide(discountFactor, RoundingPolicy.CALCULATION);

        return Money.of(presentValue, input.faceValue().currency());
    }

    /** Taxa de desconto mensal efetiva = base + spread (soma aritmetica, ver SPEC.md). */
    public BigDecimal effectiveMonthlyRate(BigDecimal monthlyBaseRate) {
        return monthlyBaseRate.add(monthlySpread(), RoundingPolicy.CALCULATION);
    }
}
