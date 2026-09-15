package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.PricingView;
import br.com.srm.creditengine.api.dto.SimulationRequest;
import br.com.srm.creditengine.application.pricing.PricingSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulacao de precificacao para o painel do operador.
 *
 * <p>{@code POST} e nao {@code GET} de proposito: a simulacao recebe um objeto de negocio
 * completo no corpo (e nao caberia bem em query string), e nao e cacheavel - o resultado
 * depende da cotacao vigente naquele instante.
 */
@RestController
@RequestMapping(path = "/api/v1/simulations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulacao", description = "Precificacao sem efeito colateral")
public class SimulationController {

    private final PricingSimulationService simulationService;

    public SimulationController(PricingSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Simula o valor presente e o desagio de um recebivel",
            description = "Nao cadastra nem liquida nada. Usa o mesmo motor e a mesma cotacao vigente da liquidacao.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precificacao calculada"),
            @ApiResponse(responseCode = "400", description = "Payload invalido", content = @Content),
            @ApiResponse(responseCode = "422", description = "Entrada sem sentido financeiro (face nao positiva, vencimento anterior)", content = @Content),
            @ApiResponse(responseCode = "503", description = "Sem cotacao vigente para o par pedido", content = @Content)
    })
    public PricingView simulate(@Valid @RequestBody SimulationRequest request) {
        return PricingView.of(simulationService.simulate(request.toCommand()));
    }
}
