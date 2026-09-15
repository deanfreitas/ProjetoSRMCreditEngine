package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.domain.fx.FxRate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Cotacao na resposta da API.
 *
 * <p>A direcao vem explicita ({@code 1 baseCurrency = rate quoteCurrency}) em vez de um
 * campo "rate" solto: o cliente nao deveria ter que adivinhar se multiplica ou divide.
 */
@Schema(name = "FxRate", description = "1 unidade de baseCurrency equivale a 'rate' unidades de quoteCurrency")
public record FxRateView(
        @Schema(example = "USD") String baseCurrency,
        @Schema(example = "BRL") String quoteCurrency,
        @Schema(example = "5.432100") String rate,
        Instant effectiveAt,
        @Schema(example = "MANUAL") String source
) {

    public static FxRateView of(FxRate rate) {
        return new FxRateView(
                rate.baseCurrency().name(),
                rate.quoteCurrency().name(),
                rate.rate().toPlainString(),
                rate.effectiveAt(),
                rate.source());
    }
}
