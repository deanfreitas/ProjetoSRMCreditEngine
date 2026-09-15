package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.settlement.SettlementOutcome;
import br.com.srm.creditengine.domain.settlement.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de liquidacao exposto pela API.
 *
 * <p>{@code replayed} informa que a requisicao foi um retry da mesma chave: nada novo foi
 * gravado e nada foi pago de novo. O cliente tambem distingue pelo status
 * ({@code 201} vs {@code 200}), mas o campo torna o comportamento explicito em log e em
 * tela de operacao.
 */
@Schema(name = "Settlement", description = "Liquidacao registrada, com as taxas aplicadas congeladas")
public record SettlementView(
        UUID id,
        UUID receivableId,
        UUID assignorId,
        String idempotencyKey,
        boolean replayed,
        int termMonths,
        String monthlyBaseRate,
        String monthlySpread,
        MoneyView faceValue,
        MoneyView presentValue,
        MoneyView discount,
        MoneyView settlementAmount,
        FxRateView fxRate,
        Instant settledAt
) {

    public static SettlementView of(SettlementOutcome outcome) {
        return of(outcome.settlement(), outcome.replayed());
    }

    public static SettlementView of(Settlement settlement, boolean replayed) {
        return new SettlementView(
                settlement.id(),
                settlement.receivableId(),
                settlement.assignorId(),
                settlement.idempotencyKey(),
                replayed,
                settlement.termMonths(),
                settlement.monthlyBaseRate().toPlainString(),
                settlement.monthlySpread().toPlainString(),
                MoneyView.of(settlement.faceValue()),
                MoneyView.of(settlement.presentValue()),
                MoneyView.of(settlement.discount()),
                MoneyView.of(settlement.settlementAmount()),
                settlement.optionalFxRate().map(FxRateView::of).orElse(null),
                settlement.settledAt());
    }
}
