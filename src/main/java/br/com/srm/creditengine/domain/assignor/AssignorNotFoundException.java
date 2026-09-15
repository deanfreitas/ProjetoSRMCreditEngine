package br.com.srm.creditengine.domain.assignor;

import br.com.srm.creditengine.domain.DomainException;

import java.util.UUID;

/** Cedente inexistente: cadastrar recebivel orfao quebraria o extrato por cedente. */
public class AssignorNotFoundException extends DomainException {

    public AssignorNotFoundException(UUID assignorId) {
        super("Cedente nao encontrado: " + assignorId);
    }

    @Override
    public String errorCode() {
        return "ASSIGNOR_NOT_FOUND";
    }
}
