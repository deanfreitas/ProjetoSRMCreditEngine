package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado da precificacao com o <b>rastro completo do calculo</b>: alem dos valores,
 * carrega as taxas usadas e o prazo aplicado.
 *
 * <p>Isso nao e enfeite: sem gravar taxa base, spread e cotacao efetivamente aplicados,
 * uma liquidacao de 6 meses atras se torna impossivel de reproduzir quando o parametro
 * muda - e auditoria/contestacao de cedente e cenario real.
 *
 * @param faceValue          valor de face, moeda de emissao
 * @param presentValue       valor presente na moeda de emissao, arredondado (2 casas, half-even)
 * @param discount           desagio na moeda de emissao (face - valor presente arredondado)
 * @param settlementAmount   valor a pagar ao cedente, na moeda de liquidacao, arredondado
 * @param termMonths         prazo em meses aplicado
 * @param monthlyBaseRate    taxa base mensal vigente usada
 * @param monthlySpread      spread mensal da strategy usada
 * @param fxRate             cotacao aplicada; ausente em liquidacao domestica
 */
public record PricingResult(
        Money faceValue,
        Money presentValue,
        Money discount,
        Money settlementAmount,
        int termMonths,
        BigDecimal monthlyBaseRate,
        BigDecimal monthlySpread,
        FxRate fxRate
) {

    public PricingResult {
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(presentValue, "presentValue");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(settlementAmount, "settlementAmount");
        Objects.requireNonNull(monthlyBaseRate, "monthlyBaseRate");
        Objects.requireNonNull(monthlySpread, "monthlySpread");
    }

    /** Taxa de desconto mensal efetiva aplicada (base + spread). */
    public BigDecimal effectiveMonthlyRate() {
        return monthlyBaseRate.add(monthlySpread);
    }

    public Optional<FxRate> optionalFxRate() {
        return Optional.ofNullable(fxRate);
    }
}
