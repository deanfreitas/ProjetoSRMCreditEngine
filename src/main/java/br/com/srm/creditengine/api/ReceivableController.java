package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.ReceivableRequest;
import br.com.srm.creditengine.api.dto.ReceivableView;
import br.com.srm.creditengine.api.dto.SettlementView;
import br.com.srm.creditengine.application.receivable.ReceivableService;
import br.com.srm.creditengine.application.settlement.SettlementService;
import br.com.srm.creditengine.domain.settlement.SettlementNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/receivables", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Recebiveis", description = "Cadastro e consulta dos ativos adquiridos")
public class ReceivableController {

    private final ReceivableService receivableService;
    private final SettlementService settlementService;

    public ReceivableController(ReceivableService receivableService, SettlementService settlementService) {
        this.receivableService = receivableService;
        this.settlementService = settlementService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cadastra um recebivel")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recebivel cadastrado"),
            @ApiResponse(responseCode = "400", description = "Payload invalido", content = @Content),
            @ApiResponse(responseCode = "404", description = "Cedente inexistente", content = @Content),
            @ApiResponse(responseCode = "422", description = "Vencimento anterior a hoje ou valor de face nao positivo", content = @Content)
    })
    public ResponseEntity<ReceivableView> register(@Valid @RequestBody ReceivableRequest request) {
        ReceivableView view = ReceivableView.of(receivableService.register(request.toCommand()));
        return ResponseEntity.created(URI.create("/api/v1/receivables/" + view.id())).body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um recebivel")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recebivel encontrado"),
            @ApiResponse(responseCode = "404", description = "Recebivel inexistente", content = @Content)
    })
    public ReceivableView findById(@PathVariable UUID id) {
        return ReceivableView.of(receivableService.findById(id));
    }

    /**
     * Liquidacao do recebivel como sub-recurso.
     *
     * <p>Um recebivel tem no maximo uma liquidacao (unicidade no banco), por isso o caminho
     * e singular e nao uma colecao.
     */
    @GetMapping("/{id}/settlement")
    @Operation(summary = "Consulta a liquidacao de um recebivel",
            description = "Registro imutavel de auditoria, com a cotacao efetivamente aplicada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liquidacao encontrada"),
            @ApiResponse(responseCode = "404", description = "Recebivel sem liquidacao registrada", content = @Content)
    })
    public SettlementView findSettlement(@PathVariable UUID id) {
        return settlementService.findByReceivable(id)
                .map(settlement -> SettlementView.of(settlement, false))
                .orElseThrow(() -> new SettlementNotFoundException(id));
    }
}
