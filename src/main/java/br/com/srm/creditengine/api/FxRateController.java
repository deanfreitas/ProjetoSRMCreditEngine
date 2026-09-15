package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.FxRateRequest;
import br.com.srm.creditengine.api.dto.FxRateView;
import br.com.srm.creditengine.application.fx.FxRateService;
import br.com.srm.creditengine.domain.money.Currency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Currency Engine exposto em HTTP.
 *
 * <p>So existe {@code POST}: cada atualizacao e uma <b>nova</b> cotacao com sua vigencia.
 * Nao ha {@code PUT} nem {@code DELETE} porque corrigir o passado invalidaria a auditoria
 * das liquidacoes ja feitas.
 */
@RestController
@RequestMapping(path = "/api/v1/fx-rates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cambio", description = "Cotacoes com vigencia e fonte")
public class FxRateController {

    private final FxRateService fxRateService;

    public FxRateController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registra uma cotacao",
            description = "Atualizacao manual da mesa ou entrada do provedor mockado. Historico e append-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cotacao registrada"),
            @ApiResponse(responseCode = "400", description = "Payload invalido", content = @Content),
            @ApiResponse(responseCode = "409", description = "Ja existe cotacao do par com essa vigencia", content = @Content),
            @ApiResponse(responseCode = "422", description = "Moedas iguais ou taxa nao positiva", content = @Content)
    })
    public FxRateView register(@Valid @RequestBody FxRateRequest request) {
        return FxRateView.of(fxRateService.register(request.toCommand()));
    }

    @GetMapping("/current")
    @Operation(summary = "Cotacao que uma liquidacao feita agora usaria",
            description = "Aplica a mesma regra de vigencia e defasagem da liquidacao: taxa velha nao e devolvida como valida.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cotacao vigente"),
            @ApiResponse(responseCode = "503", description = "Sem cotacao vigente ou cotacao defasada", content = @Content)
    })
    public FxRateView current(
            @RequestParam @Parameter(example = "USD") Currency base,
            @RequestParam @Parameter(example = "BRL") Currency quote) {
        return FxRateView.of(fxRateService.current(base, quote));
    }

    @GetMapping
    @Operation(summary = "Historico de cotacoes do par, da mais recente para a mais antiga")
    public List<FxRateView> history(
            @RequestParam @Parameter(example = "USD") Currency base,
            @RequestParam @Parameter(example = "BRL") Currency quote,
            @RequestParam(defaultValue = "20") int limit) {
        return fxRateService.history(base, quote, Math.clamp(limit, 1, 200)).stream()
                .map(FxRateView::of)
                .toList();
    }
}
