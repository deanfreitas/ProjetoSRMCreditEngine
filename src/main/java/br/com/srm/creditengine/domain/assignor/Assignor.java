package br.com.srm.creditengine.domain.assignor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Cedente: quem vende o recebivel ao fundo. */
public record Assignor(UUID id, String document, String legalName, Instant createdAt) {

    public Assignor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(legalName, "legalName");
        if (!document.matches("\\d{11}|\\d{14}")) {
            throw new IllegalArgumentException("Documento deve ser CPF (11) ou CNPJ (14) digitos: " + document);
        }
    }
}
