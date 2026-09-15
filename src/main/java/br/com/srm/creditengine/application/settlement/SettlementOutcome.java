package br.com.srm.creditengine.application.settlement;

import br.com.srm.creditengine.domain.settlement.Settlement;

/**
 * Resultado da liquidacao com a informacao de <b>como</b> ela terminou.
 *
 * @param settlement registro gravado (ou o ja existente, em caso de repeticao)
 * @param replayed   {@code true} quando a requisicao foi um retry da mesma chave de
 *                   idempotencia: nada novo foi gravado e nada foi pago de novo.
 *                   A camada HTTP usa isso para responder {@code 201} vs {@code 200}.
 */
public record SettlementOutcome(Settlement settlement, boolean replayed) {

    public static SettlementOutcome created(Settlement settlement) {
        return new SettlementOutcome(settlement, false);
    }

    public static SettlementOutcome replayed(Settlement settlement) {
        return new SettlementOutcome(settlement, true);
    }
}
