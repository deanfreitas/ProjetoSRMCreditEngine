package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.domain.pricing.PricingResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resultado da precificacao exposto pela API.
 *
 * <p>Devolve o <b>rastro do calculo</b> (prazo, taxa base, spread, taxa efetiva e cotacao),
 * nao apenas o numero final. Quem opera precisa poder explicar ao cedente por que o
 * desagio foi aquele - e a mesa confere a taxa aplicada antes de fechar.
 */
@Schema(name = "Pricing", description = "Valor presente, desagio e as taxas efetivamente aplicadas")
public record PricingView(
        MoneyView faceValue,
        MoneyView presentValue,
        MoneyView discount,
        MoneyView settlementAmount,
        @Schema(example = "3") int termMonths,
        @Schema(example = "0.010000") String monthlyBaseRate,
        @Schema(example = "0.015000") String monthlySpread,
        @Schema(example = "0.025000") String effectiveMonthlyRate,
        @Schema(description = "Cotacao aplicada; ausente em liquidacao na mesma moeda") FxRateView fxRate
) {

    public static PricingView of(PricingResult result) {
        return new PricingView(
                MoneyView.of(result.faceValue()),
                MoneyView.of(result.presentValue()),
                MoneyView.of(result.discount()),
                MoneyView.of(result.settlementAmount()),
                result.termMonths(),
                result.monthlyBaseRate().toPlainString(),
                result.monthlySpread().toPlainString(),
                result.effectiveMonthlyRate().toPlainString(),
                result.optionalFxRate().map(FxRateView::of).orElse(null));
    }
}
