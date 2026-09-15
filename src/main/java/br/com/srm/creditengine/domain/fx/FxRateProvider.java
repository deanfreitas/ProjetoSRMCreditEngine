package br.com.srm.creditengine.domain.fx;

import br.com.srm.creditengine.domain.money.Currency;

import java.time.Instant;

/**
 * Fonte da cotacao usada na liquidacao.
 *
 * <p>Abstrai "de onde vem a taxa": historico no banco hoje, provedor externo amanha.
 * Contrato explicito: <b>ou devolve cotacao valida, ou falha</b>. Nao existe retorno
 * vazio silencioso nem taxa default - liquidar com cambio errado e pior do que nao
 * liquidar (SPEC.md, secao 2.5).
 */
public interface FxRateProvider {

    /**
     * @throws FxRateUnavailableException quando nao ha cotacao vigente confiavel
     */
    FxRate rateFor(Currency baseCurrency, Currency quoteCurrency, Instant at);
}
