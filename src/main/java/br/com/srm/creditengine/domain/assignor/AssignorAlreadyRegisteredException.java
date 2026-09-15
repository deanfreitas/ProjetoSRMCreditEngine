package br.com.srm.creditengine.domain.assignor;

import br.com.srm.creditengine.domain.DomainException;

/**
 * Documento ja cadastrado. E conflito de estado (409), nao erro de formato (400): o
 * pedido era valido, mas colide com um cedente existente.
 */
public class AssignorAlreadyRegisteredException extends DomainException {

    public AssignorAlreadyRegisteredException(String document) {
        super("Ja existe cedente com o documento " + document);
    }

    @Override
    public String errorCode() {
        return "ASSIGNOR_ALREADY_REGISTERED";
    }
}
