package br.com.srm.creditengine.domain.receivable;

import br.com.srm.creditengine.domain.DomainException;

import java.util.UUID;

/** Recebivel inexistente: 404, nao 200 com corpo vazio. */
public class ReceivableNotFoundException extends DomainException {

    public ReceivableNotFoundException(UUID receivableId) {
        super("Recebivel nao encontrado: " + receivableId);
    }

    @Override
    public String errorCode() {
        return "RECEIVABLE_NOT_FOUND";
    }
}
