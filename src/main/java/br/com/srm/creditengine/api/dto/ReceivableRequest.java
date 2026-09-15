package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.receivable.RegisterReceivableCommand;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "ReceivableRequest")
public record ReceivableRequest(

        @NotNull UUID assignorId,

        @NotNull @Schema(example = "DUPLICATA_MERCANTIL") ReceivableType type,

        @NotNull
        @Pattern(regexp = "\\d{1,15}(\\.\\d{1,2})?", message = "deve ser decimal positivo com no maximo 2 casas, ex.: 100000.00")
        @Schema(example = "100000.00") String faceValue,

        @NotNull @Schema(example = "BRL") Currency faceCurrency,

        @NotNull @FutureOrPresent(message = "vencimento nao pode estar no passado")
        @Schema(example = "2026-03-15") LocalDate dueDate
) {

    public RegisterReceivableCommand toCommand() {
        return new RegisterReceivableCommand(
                assignorId, type, new BigDecimal(faceValue), faceCurrency, dueDate);
    }
}
