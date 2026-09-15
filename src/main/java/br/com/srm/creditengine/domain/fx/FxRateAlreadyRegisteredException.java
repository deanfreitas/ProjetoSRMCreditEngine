package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.DomainException;
import br.com.srm.creditengine.domain.money.Currency;

import java.time.Instant;

/**
 * Ja existe cotacao do par com essa vigencia.
 *
 * <p>Sobrescrever seria reescrever historico: uma liquidacao feita ontem passaria a
 * "ter usado" outra taxa. Corrigir cotacao errada se faz registrando nova vigencia.
 */
public class FxRateAlreadyRegisteredException extends DomainException {

    public FxRateAlreadyRegisteredException(Currency baseCurrency, Currency quoteCurrency, Instant effectiveAt) {
        super("Ja existe cotacao %s/%s com vigencia em %s".formatted(baseCurrency, quoteCurrency, effectiveAt));
    }

    @Override
    public String errorCode() {
        return "FX_RATE_ALREADY_REGISTERED";
    }
}
