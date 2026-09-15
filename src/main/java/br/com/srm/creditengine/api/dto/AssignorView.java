package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.domain.assignor.Assignor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "Assignor")
public record AssignorView(UUID id, String document, String legalName, Instant createdAt) {

    public static AssignorView of(Assignor assignor) {
        return new AssignorView(assignor.id(), assignor.document(), assignor.legalName(), assignor.createdAt());
    }
}
