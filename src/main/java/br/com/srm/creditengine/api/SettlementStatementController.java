package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.StatementView;
import br.com.srm.creditengine.application.statement.SettlementStatementQuery;
import br.com.srm.creditengine.application.statement.StatementFilter;
import br.com.srm.creditengine.domain.money.Currency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Extrato analitico de liquidacoes.
 *
 * <p>Este recurso conversa direto com a porta de leitura em SQL nativo, sem passar por
 * servico de negocio: relatorio nao tem regra de dominio para aplicar, e o enunciado
 * autoriza explicitamente o atalho de camadas para relatorios (item 4.1.7). Inventar um
 * service que so repassa a chamada seria camada vazia.
 */
@RestController
@RequestMapping(path = "/api/v1/settlements/statement", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Extrato", description = "Relatorio de liquidacoes com filtros e paginacao server-side")
public class SettlementStatementController {

    private final SettlementStatementQuery statementQuery;

    public SettlementStatementController(SettlementStatementQuery statementQuery) {
        this.statementQuery = statementQuery;
    }

    @GetMapping
    @Operation(summary = "Extrato de liquidacoes por periodo, cedente e moeda",
            description = "Paginacao e totalizacao acontecem no banco. O periodo tem inicio inclusivo e fim exclusivo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina do extrato, com os totais do filtro inteiro"),
            @ApiResponse(responseCode = "400", description = "Parametro com tipo invalido", content = @Content),
            @ApiResponse(responseCode = "422", description = "Periodo invertido ou tamanho de pagina fora do limite", content = @Content)
    })
    public StatementView statement(
            @RequestParam(required = false)
            @Parameter(description = "Inicio do periodo, inclusivo (ISO-8601)", example = "2026-01-01T00:00:00Z")
            Instant from,

            @RequestParam(required = false)
            @Parameter(description = "Fim do periodo, exclusivo (ISO-8601)", example = "2026-02-01T00:00:00Z")
            Instant to,

            @RequestParam(required = false)
            @Parameter(description = "Filtra por cedente")
            UUID assignorId,

            @RequestParam(required = false)
            @Parameter(description = "Filtra pela moeda em que o cedente recebeu", example = "USD")
            Currency currency,

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "20")
            @Parameter(description = "Maximo de 200 registros por pagina")
            int size) {

        StatementFilter filter = new StatementFilter(from, to, assignorId, currency, page, size);
        return StatementView.of(statementQuery.findBy(filter));
    }
}
