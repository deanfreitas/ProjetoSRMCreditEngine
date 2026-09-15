package br.com.srm.creditengine.domain.settlement;

import br.com.srm.creditengine.domain.DomainException;

/**
 * Mesma {@code Idempotency-Key} reenviada com payload diferente.
 *
 * <p>Devolver a liquidacao anterior seria mentir para o cliente (ele pediu outra coisa);
 * executar seria violar a chave. A resposta correta e {@code 409}.
 */
public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Chave de idempotencia '%s' ja foi usada com outro payload".formatted(idempotencyKey));
    }

    @Override
    public String errorCode() {
        return "IDEMPOTENCY_KEY_CONFLICT";
    }
}
