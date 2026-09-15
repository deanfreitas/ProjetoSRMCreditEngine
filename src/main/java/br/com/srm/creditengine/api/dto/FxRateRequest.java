package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.fx.RegisterFxRateCommand;
import br.com.srm.creditengine.domain.money.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Atualizacao manual de cotacao (ou entrada do provedor mockado).
 *
 * <p>Ate 6 casas decimais, o mesmo que {@code NUMERIC(19,6)} no banco: aceitar mais casas
 * na API do que o banco guarda faria a taxa gravada divergir da taxa enviada.
 */
@Schema(name = "FxRateRequest", description = "1 unidade de baseCurrency equivale a 'rate' unidades de quoteCurrency")
public record FxRateRequest(

        @NotNull @Schema(example = "USD") Currency baseCurrency,

        @NotNull @Schema(example = "BRL") Currency quoteCurrency,

        @NotNull
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,6})?", message = "deve ser decimal positivo com no maximo 6 casas, ex.: 5.4321")
        @Schema(example = "5.4321") String rate,

        @Schema(description = "Vigencia da cotacao; ausente significa agora") Instant effectiveAt,

        @Size(max = 40) @Schema(example = "MESA_OPERACOES", description = "Origem da cotacao; ausente vira MANUAL") String source
) {

    public RegisterFxRateCommand toCommand() {
        return new RegisterFxRateCommand(baseCurrency, quoteCurrency, new BigDecimal(rate), effectiveAt, source);
    }
}
