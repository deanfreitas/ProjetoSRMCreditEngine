package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.money.Money;

import java.math.BigDecimal;

/**
 * Regra de precificacao de um tipo de recebivel (padrao Strategy).
 *
 * <p>Contrato deliberado: a strategy devolve o valor presente <b>sem arredondamento</b>.
 * Quem arredonda e o {@link PricingEngine}, uma unica vez, no fim. Se cada strategy
 * arredondasse, a politica de precisao viraria N politicas.
 *
 * <p>A strategy tambem nao converte moeda: conversao cambial e responsabilidade do motor,
 * porque a ordem "arredonda em BRL, depois converte" e uma decisao de produto (SPEC.md),
 * nao de tipo de ativo.
 */
public interface PricingStrategy {

    /** Tipo atendido por esta strategy. */
    ReceivableType receivableType();

    /** Spread mensal em forma decimal (0.015 = 1,5% a.m.). */
    BigDecimal monthlySpread();

    /**
     * Valor presente na moeda de face, com precisao plena.
     *
     * @param input           dados da operacao
     * @param monthlyBaseRate taxa base mensal vigente, decimal (0.01 = 1% a.m.)
     */
    Money unroundedPresentValue(PricingInput input, BigDecimal monthlyBaseRate);
}
