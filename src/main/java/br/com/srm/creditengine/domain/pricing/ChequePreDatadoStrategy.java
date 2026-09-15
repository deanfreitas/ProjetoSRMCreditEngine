package br.com.srm.creditengine.domain.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** Cheque Pre-datado: spread 2,5% a.m. (default), risco pulverizado e maior inadimplencia. */
public class ChequePreDatadoStrategy extends CompoundDiscountStrategy {

    public static final BigDecimal DEFAULT_MONTHLY_SPREAD = new BigDecimal("0.025");

    private final BigDecimal monthlySpread;

    public ChequePreDatadoStrategy() {
        this(DEFAULT_MONTHLY_SPREAD);
    }

    public ChequePreDatadoStrategy(BigDecimal monthlySpread) {
        this.monthlySpread = Objects.requireNonNull(monthlySpread, "monthlySpread");
    }

    @Override
    public ReceivableType receivableType() {
        return ReceivableType.CHEQUE_PRE_DATADO;
    }

    @Override
    public BigDecimal monthlySpread() {
        return monthlySpread;
    }
}
