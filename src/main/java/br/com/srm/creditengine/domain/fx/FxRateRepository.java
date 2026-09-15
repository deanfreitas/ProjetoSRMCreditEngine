package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.money.Currency;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Porta de persistencia do historico de cotacoes. Cotacao gravada nao e alterada. */
public interface FxRateRepository {

    FxRate save(FxRate rate);

    /** Cotacao vigente mais recente do par em {@code at} (nunca uma cotacao futura). */
    Optional<FxRate> findLatest(Currency baseCurrency, Currency quoteCurrency, Instant at);

    List<FxRate> findHistory(Currency baseCurrency, Currency quoteCurrency, int limit);
}
