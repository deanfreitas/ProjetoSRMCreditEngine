package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;

/**
 * Grava no historico a cotacao trazida do provedor externo.
 *
 * <p>Existe como porta separada do repositorio por causa da <b>transacao</b>: a cotacao e
 * buscada no meio de uma liquidacao, e precisa sobreviver ao rollback dela (ver
 * {@link TransactionalFxRateWriter}). Como porta, tambem deixa o
 * {@link ResilientFxRateProvider} testavel sem banco.
 */
public interface FxRateWriter {

    FxRate store(FxRate rate);
}
