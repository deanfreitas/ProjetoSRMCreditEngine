package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.DomainException;

/** Entrada que nao faz sentido financeiro (face nao positiva, prazo negativo). */
public class InvalidPricingInputException extends DomainException {

    public InvalidPricingInputException(String message) {
        super(message);
    }

    @Override
    public String errorCode() {
        return "INVALID_PRICING_INPUT";
    }
}
