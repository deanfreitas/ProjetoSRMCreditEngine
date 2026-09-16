package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.SettlementRequest;
import br.com.srm.creditengine.api.dto.SettlementView;
import br.com.srm.creditengine.application.settlement.SettlementOutcome;
import br.com.srm.creditengine.application.settlement.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Endpoint de liquidacao - o mesmo do Anexo A, escrito para nao pagar duas vezes.
 *
 * <p>Contrato de idempotencia visivel no HTTP:
 * <ul>
 *   <li>{@code Idempotency-Key} e <b>obrigatorio</b>; ausente = {@code 400}. Sem chave nao
 *       existe retry seguro, e retry vai acontecer (rede e duplo clique);</li>
 *   <li>primeira execucao devolve {@code 201} com {@code Location};</li>
 *   <li>repeticao da mesma chave com o mesmo corpo devolve {@code 200} e a liquidacao
 *       original - nada novo e gravado;</li>
 *   <li>mesma chave com corpo diferente devolve {@code 409}: o cliente pediu outra coisa
 *       reusando a chave, e devolver a liquidacao anterior seria mentir para ele;</li>
 *   <li>mesma chave e mesmo corpo <b>enquanto a primeira ainda roda</b> devolve
 *       {@code 409} com {@code errorCode=SETTLEMENT_IN_PROGRESS}: aqui repetir resolve, e a
 *       repeticao seguinte cai no caso do {@code 200}. Recusar cedo evita que as duas
 *       requisicoes resolvam cotacao e precifiquem para uma delas ser descartada.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/settlements", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Liquidacao", description = "Registro de pagamento ao cedente, idempotente e auditavel")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Liquida um recebivel",
            description = "Atomica e idempotente. A cotacao usada e congelada no registro de auditoria.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Liquidacao registrada"),
            @ApiResponse(responseCode = "200",
                    description = "Repeticao da mesma Idempotency-Key: devolve a liquidacao original"),
            @ApiResponse(responseCode = "400", description = "Payload invalido ou Idempotency-Key ausente", content = @Content),
            @ApiResponse(responseCode = "404", description = "Recebivel inexistente", content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "Recebivel ja liquidado, liquidacao concorrente, chave reusada com outro payload "
                            + "ou chave ainda em processamento (SETTLEMENT_IN_PROGRESS: repita em instantes)",
                    content = @Content),
            @ApiResponse(responseCode = "503",
                    description = "Cotacao indisponivel ou defasada: nada foi gravado, pode repetir",
                    content = @Content)
    })
    public ResponseEntity<SettlementView> settle(
            @RequestHeader("Idempotency-Key")
            @NotBlank
            @Size(max = 120, message = "no maximo 120 caracteres")
            @Parameter(description = "Chave unica do pedido; repetir a mesma chave nao gera nova liquidacao",
                    required = true, example = "8f1c2f7a-3f1e-4c2b-9a44-2b6b2a1d9f10")
            String idempotencyKey,

            @Valid @RequestBody SettlementRequest request) {

        SettlementOutcome outcome = settlementService.settle(request.toCommand(idempotencyKey));
        SettlementView view = SettlementView.of(outcome);
        URI location = URI.create("/api/v1/receivables/" + outcome.settlement().receivableId() + "/settlement");

        return outcome.replayed()
                ? ResponseEntity.ok().location(location).body(view)
                : ResponseEntity.created(location).body(view);
    }
}
