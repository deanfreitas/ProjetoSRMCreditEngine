package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;

import java.util.Optional;

/**
 * Porta para o provedor externo de cotacao (PTAX, mesa, agregador de mercado). Aqui a
 * implementacao e mockada, mas o contrato e o de um terceiro de verdade: pode demorar,
 * pode cair e pode simplesmente nao conhecer o par pedido.
 *
 * <p>A distincao entre os dois modos de "nao deu" e deliberada e aparece na resiliencia:
 *
 * <ul>
 *   <li>{@link Optional#empty()} - o terceiro respondeu e disse que nao cota esse par.
 *       E resposta valida: nao se repete a chamada nem se abre disjuntor por isso.</li>
 *   <li>{@link FxRateSourceException} - falha tecnica (timeout, 5xx, conexao). Essa
 *       conta para retry e para o disjuntor.</li>
 * </ul>
 *
 * <p>Tratar as duas do mesmo jeito levaria a retentar 3 vezes uma pergunta cuja resposta
 * nao vai mudar, e a abrir o disjuntor por causa de um par mal configurado.
 */
public interface ExternalFxRateSource {

    Optional<FxRate> fetch(Currency baseCurrency, Currency quoteCurrency);
}
