package br.com.srm.creditengine.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "AssignorRequest")
public record AssignorRequest(

        @NotBlank
        @Pattern(regexp = "\\d{11}|\\d{14}", message = "CPF (11) ou CNPJ (14) digitos, sem pontuacao")
        @Schema(example = "12345678000199") String document,

        @NotBlank @Size(max = 200) @Schema(example = "Industria Ipiranga Ltda") String legalName
) {
}
