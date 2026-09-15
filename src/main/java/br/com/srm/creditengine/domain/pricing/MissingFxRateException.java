package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.DomainException;
import br.com.srm.creditengine.domain.money.Currency;

/** Liquidacao cross-currency sem cotacao resolvida: nao existe "taxa 1:1 por omissao". */
public class MissingFxRateException extends DomainException {

    public MissingFxRateException(Currency from, Currency to) {
        super("Liquidacao %s -> %s exige cotacao de cambio vigente".formatted(from, to));
    }

    @Override
    public String errorCode() {
        return "MISSING_FX_RATE";
    }
}
