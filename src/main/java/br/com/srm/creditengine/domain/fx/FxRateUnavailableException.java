package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.DomainException;
import br.com.srm.creditengine.domain.money.Currency;

/**
 * Nao ha cotacao vigente confiavel para o par (sem historico, provedor indisponivel ou
 * circuito aberto). Mapeia para {@code 503}: e falha de dependencia, nao erro do cliente.
 */
public class FxRateUnavailableException extends DomainException {

    public FxRateUnavailableException(Currency baseCurrency, Currency quoteCurrency, String reason) {
        super("Cotacao %s/%s indisponivel: %s".formatted(baseCurrency, quoteCurrency, reason));
    }

    public FxRateUnavailableException(Currency baseCurrency, Currency quoteCurrency, Throwable cause) {
        super("Cotacao %s/%s indisponivel".formatted(baseCurrency, quoteCurrency), cause);
    }

    @Override
    public String errorCode() {
        return "FX_RATE_UNAVAILABLE";
    }
}
