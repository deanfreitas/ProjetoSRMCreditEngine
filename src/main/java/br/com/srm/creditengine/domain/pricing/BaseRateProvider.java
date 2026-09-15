package br.com.srm.creditengine.domain.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Origem da taxa base mensal (ambiguidade resolvida no SPEC.md: taxa base e parametro
 * operacional do fundo, com vigencia, e nao constante de codigo).
 *
 * <p>A assinatura recebe a data de referencia para permitir vigencia: hoje a implementacao
 * e por configuracao, amanha pode ser uma tabela de taxas historicas ou CDI importado,
 * sem tocar no motor.
 */
public interface BaseRateProvider {

    /**
     * @param referenceDate data da operacao
     * @return taxa mensal em forma decimal (0.01 = 1% a.m.)
     */
    BigDecimal monthlyBaseRate(LocalDate referenceDate);
}
