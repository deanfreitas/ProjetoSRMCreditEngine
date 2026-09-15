package br.com.srm.creditengine.config;

import br.com.srm.creditengine.domain.pricing.ChequePreDatadoStrategy;
import br.com.srm.creditengine.domain.pricing.DuplicataMercantilStrategy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Parametros de precificacao externalizados (taxa base e spreads), em decimal:
 * {@code 0.01} = 1% a.m.
 *
 * <p>Sao parametros de negocio, nao constantes de codigo: a mesa muda taxa sem deploy,
 * e o valor efetivamente aplicado fica gravado em cada liquidacao. Valores default
 * coincidem com os golden cases.
 */
@Validated
@ConfigurationProperties(prefix = "credit-engine.pricing")
public class PricingProperties {

    /** Taxa base mensal vigente (decimal). */
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal baseMonthlyRate = new BigDecimal("0.01");

    /** Spread mensal da duplicata mercantil (decimal). */
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal duplicataMonthlySpread = DuplicataMercantilStrategy.DEFAULT_MONTHLY_SPREAD;

    /** Spread mensal do cheque pre-datado (decimal). */
    @NotNull
    @DecimalMin("0.0")
    private BigDecimal chequeMonthlySpread = ChequePreDatadoStrategy.DEFAULT_MONTHLY_SPREAD;

    public BigDecimal getBaseMonthlyRate() {
        return baseMonthlyRate;
    }

    public void setBaseMonthlyRate(BigDecimal baseMonthlyRate) {
        this.baseMonthlyRate = baseMonthlyRate;
    }

    public BigDecimal getDuplicataMonthlySpread() {
        return duplicataMonthlySpread;
    }

    public void setDuplicataMonthlySpread(BigDecimal duplicataMonthlySpread) {
        this.duplicataMonthlySpread = duplicataMonthlySpread;
    }

    public BigDecimal getChequeMonthlySpread() {
        return chequeMonthlySpread;
    }

    public void setChequeMonthlySpread(BigDecimal chequeMonthlySpread) {
        this.chequeMonthlySpread = chequeMonthlySpread;
    }
}
