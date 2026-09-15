package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.settlement.SettleReceivableCommand;
import br.com.srm.creditengine.domain.money.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Pedido de liquidacao.
 *
 * <p>A chave de idempotencia <b>nao</b> esta no corpo: ela vem no header
 * {@code Idempotency-Key}, porque e propriedade da requisicao (retry de rede reenvia o
 * mesmo corpo com o mesmo header), nao do negocio.
 */
@Schema(name = "SettlementRequest")
public record SettlementRequest(

        @NotNull UUID receivableId,

        @NotNull @Schema(example = "USD", description = "Moeda em que o cedente recebe") Currency settlementCurrency
) {

    public SettleReceivableCommand toCommand(String idempotencyKey) {
        return new SettleReceivableCommand(receivableId, settlementCurrency, idempotencyKey);
    }
}
