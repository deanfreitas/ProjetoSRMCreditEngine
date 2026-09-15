package br.com.srm.creditengine.api;

import br.com.srm.creditengine.api.dto.AssignorRequest;
import br.com.srm.creditengine.api.dto.AssignorView;
import br.com.srm.creditengine.application.assignor.AssignorService;
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
@RequestMapping(path = "/api/v1/assignors", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cedentes", description = "Cadastro de quem vende o recebivel ao fundo")
public class AssignorController {

    private final AssignorService assignorService;

    public AssignorController(AssignorService assignorService) {
        this.assignorService = assignorService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cadastra um cedente")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cedente cadastrado"),
            @ApiResponse(responseCode = "400", description = "Documento fora do formato de CPF/CNPJ", content = @Content),
            @ApiResponse(responseCode = "409", description = "Documento ja cadastrado", content = @Content)
    })
    public ResponseEntity<AssignorView> register(@Valid @RequestBody AssignorRequest request) {
        AssignorView view = AssignorView.of(assignorService.register(request.document(), request.legalName()));
        return ResponseEntity.created(URI.create("/api/v1/assignors/" + view.id())).body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um cedente")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cedente encontrado"),
            @ApiResponse(responseCode = "404", description = "Cedente inexistente", content = @Content)
    })
    public AssignorView findById(@PathVariable UUID id) {
        return AssignorView.of(assignorService.findById(id));
    }
}
