package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.money.RoundingPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Cotacao vigente a partir de {@code effectiveAt}: <b>1 unidade de {@code baseCurrency}
 * equivale a {@code rate} unidades de {@code quoteCurrency}</b>.
 *
 * <p>Exemplo do golden case C3: base USD, quote BRL, rate 5,4321 (US$ 1,00 = R$ 5,4321).
 * A direcao e explicita no tipo porque inverter cotacao e o erro classico de sistema
 * multimoeda (o Anexo A dividia por uma taxa sem saber em que direcao ela estava).
 *
 * <p>{@code effectiveAt} e {@code source} existem para auditabilidade: a liquidacao
 * guarda a taxa efetivamente usada, nao um ponteiro para "a taxa atual".
 */
public record FxRate(
        Currency baseCurrency,
        Currency quoteCurrency,
        BigDecimal rate,
        Instant effectiveAt,
        String source
) {

    public FxRate {
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        Objects.requireNonNull(quoteCurrency, "quoteCurrency");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (baseCurrency == quoteCurrency) {
            throw new IllegalArgumentException("Cotacao exige moedas distintas: " + baseCurrency);
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Taxa de cambio deve ser positiva: " + rate);
        }
    }

    public static FxRate of(Currency base, Currency quote, String rate, Instant effectiveAt, String source) {
        return new FxRate(base, quote, new BigDecimal(rate), effectiveAt, source);
    }

    public boolean supports(Currency from, Currency to) {
        return (from == quoteCurrency && to == baseCurrency) || (from == baseCurrency && to == quoteCurrency);
    }

    /**
     * Converte sem arredondar: o arredondamento monetario e responsabilidade de quem
     * fecha o calculo (ver {@link RoundingPolicy}).
     */
    public Money convert(Money amount, Currency target) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(target, "target");
        if (amount.currency() == target) {
            return amount;
        }
        if (!supports(amount.currency(), target)) {
            throw new FxRateNotApplicableException(amount.currency(), target, this);
        }
        if (target == baseCurrency) {
            return Money.of(amount.amount().divide(rate, RoundingPolicy.CALCULATION), target);
        }
        return Money.of(amount.amount().multiply(rate, RoundingPolicy.CALCULATION), target);
    }
}
