package br.com.srm.creditengine.domain.settlement;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro imutavel de liquidacao: e o documento de auditoria da operacao.
 *
 * <p>Guarda <b>copias</b> dos valores e das taxas aplicadas (base, spread e cotacao com
 * vigencia e fonte), nao referencias. Se a tabela de cotacoes ou o parametro de taxa mudar
 * amanha, este registro continua reproduzivel - que e a pergunta que a auditoria faz.
 *
 * <p>{@code requestFingerprint} guarda o hash do payload que originou a liquidacao, para
 * distinguir "mesmo request repetido" (idempotencia legitima) de "chave reaproveitada com
 * payload diferente" (erro do cliente).
 */
public record Settlement(
        UUID id,
        UUID receivableId,
        UUID assignorId,
        String idempotencyKey,
        String requestFingerprint,
        int termMonths,
        BigDecimal monthlyBaseRate,
        BigDecimal monthlySpread,
        Money faceValue,
        Money presentValue,
        Money discount,
        Money settlementAmount,
        FxRate fxRate,
        Instant settledAt
) {

    public Settlement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receivableId, "receivableId");
        Objects.requireNonNull(assignorId, "assignorId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(monthlyBaseRate, "monthlyBaseRate");
        Objects.requireNonNull(monthlySpread, "monthlySpread");
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(presentValue, "presentValue");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(settlementAmount, "settlementAmount");
        Objects.requireNonNull(settledAt, "settledAt");
        // compara os parametros locais: dentro do compact constructor os campos da
        // instancia ainda nao estao atribuidos
        if (settlementAmount.currency() != faceValue.currency() && fxRate == null) {
            throw new IllegalArgumentException(
                    "Liquidacao cross-currency exige cotacao registrada: " + receivableId);
        }
    }

    /** Traduz o resultado do motor em registro de auditoria. */
    public static Settlement from(UUID id,
                                  UUID receivableId,
                                  UUID assignorId,
                                  String idempotencyKey,
                                  String requestFingerprint,
                                  PricingResult pricing,
                                  Instant settledAt) {
        return new Settlement(
                id,
                receivableId,
                assignorId,
                idempotencyKey,
                requestFingerprint,
                pricing.termMonths(),
                pricing.monthlyBaseRate(),
                pricing.monthlySpread(),
                pricing.faceValue(),
                pricing.presentValue(),
                pricing.discount(),
                pricing.settlementAmount(),
                pricing.fxRate(),
                settledAt);
    }

    public boolean isCrossCurrency() {
        return settlementAmount.currency() != faceValue.currency();
    }

    public Optional<FxRate> optionalFxRate() {
        return Optional.ofNullable(fxRate);
    }
}
