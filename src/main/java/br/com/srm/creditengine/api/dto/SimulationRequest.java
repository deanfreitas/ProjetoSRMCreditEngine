package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.pricing.SimulatePricingCommand;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Entrada da simulacao do painel do operador.
 *
 * <p>{@code faceValue} e string validada por regex, nao {@code number}: aceitar numero JSON
 * significa aceitar o que o cliente perdeu de precisao antes de enviar. A regex tambem
 * impede notacao cientifica e mais de duas casas decimais.
 */
@Schema(name = "SimulationRequest")
public record SimulationRequest(

        @NotNull
        @Pattern(regexp = "\\d{1,15}(\\.\\d{1,2})?", message = "deve ser decimal positivo com no maximo 2 casas, ex.: 100000.00")
        @Schema(example = "100000.00", description = "Valor de face como string decimal")
        String faceValue,

        @NotNull @Schema(example = "BRL") Currency faceCurrency,

        @NotNull @Schema(example = "DUPLICATA_MERCANTIL") ReceivableType type,

        @NotNull @FutureOrPresent(message = "vencimento nao pode estar no passado")
        @Schema(example = "2026-03-15") LocalDate dueDate,

        @NotNull @Schema(example = "USD", description = "Moeda em que o cedente recebe") Currency settlementCurrency
) {

    public SimulatePricingCommand toCommand() {
        return new SimulatePricingCommand(
                new BigDecimal(faceValue), faceCurrency, type, dueDate, settlementCurrency);
    }
}
