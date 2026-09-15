package br.com.srm.creditengine.domain.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Taxa base unica, vinda de configuracao (default 1% a.m., igual aos golden cases).
 *
 * <p>Escolha consciente de escopo: enquanto nao existe curva de juros nem historico de
 * vigencia no banco, uma taxa parametrizada por ambiente e suficiente e honesta.
 * A troca por uma tabela versionada nao afeta o motor, so este bean.
 */
public class FixedBaseRateProvider implements BaseRateProvider {

    private final BigDecimal monthlyBaseRate;

    public FixedBaseRateProvider(BigDecimal monthlyBaseRate) {
        Objects.requireNonNull(monthlyBaseRate, "monthlyBaseRate");
        if (monthlyBaseRate.signum() < 0) {
            throw new IllegalArgumentException("Taxa base nao pode ser negativa: " + monthlyBaseRate);
        }
        this.monthlyBaseRate = monthlyBaseRate;
    }

    @Override
    public BigDecimal monthlyBaseRate(LocalDate referenceDate) {
        return monthlyBaseRate;
    }
}
