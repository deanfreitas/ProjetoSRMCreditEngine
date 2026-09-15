package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.DomainException;
import br.com.srm.creditengine.domain.money.Currency;

/** Cotacao fornecida nao cobre o par pedido (ex.: EUR/BRL para converter BRL em USD). */
public class FxRateNotApplicableException extends DomainException {

    public FxRateNotApplicableException(Currency from, Currency to, FxRate rate) {
        super("Cotacao %s/%s nao converte %s em %s".formatted(
                rate.baseCurrency(), rate.quoteCurrency(), from, to));
    }

    @Override
    public String errorCode() {
        return "FX_RATE_NOT_APPLICABLE";
    }
}
