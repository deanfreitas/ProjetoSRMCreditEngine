package br.com.srm.creditengine.domain.settlement;

import br.com.srm.creditengine.domain.DomainException;

/**
 * Mesma {@code Idempotency-Key} e mesmo payload chegando enquanto a primeira requisicao
 * ainda esta sendo processada.
 *
 * <p>Recusar cedo e mais honesto (e mais barato) que deixar as duas resolverem cotacao e
 * precificarem para uma perder na unicidade do banco depois. O cliente repete em seguida e
 * recebe {@code 200} com a liquidacao original.
 */
public class SettlementInProgressException extends DomainException {

    public SettlementInProgressException(String idempotencyKey) {
        super("Liquidacao com a chave '%s' esta em processamento; repita a requisicao em instantes"
                .formatted(idempotencyKey));
    }

    @Override
    public String errorCode() {
        return "SETTLEMENT_IN_PROGRESS";
    }
}
