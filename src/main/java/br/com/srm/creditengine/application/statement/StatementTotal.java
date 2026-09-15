package br.com.srm.creditengine.application.statement;

import br.com.srm.creditengine.domain.money.Money;

/**
 * Totalizador do extrato, agrupado por par (moeda de face, moeda de liquidacao).
 *
 * <p>O agrupamento inclui as <b>duas</b> moedas de proposito: somar valor de face em BRL
 * com valor pago em USD na mesma linha produziria um numero sem significado. Somatorio de
 * dinheiro so existe dentro de uma moeda.
 */
public record StatementTotal(
        long settlements,
        Money faceValue,
        Money presentValue,
        Money discount,
        Money settlementAmount
) {
}
