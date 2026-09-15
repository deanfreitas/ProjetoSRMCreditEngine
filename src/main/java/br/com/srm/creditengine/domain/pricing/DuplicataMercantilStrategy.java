package br.com.srm.creditengine.domain.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** Duplicata Mercantil: spread 1,5% a.m. (default), risco sacado corporativo. */
public class DuplicataMercantilStrategy extends CompoundDiscountStrategy {

    public static final BigDecimal DEFAULT_MONTHLY_SPREAD = new BigDecimal("0.015");

    private final BigDecimal monthlySpread;

    public DuplicataMercantilStrategy() {
        this(DEFAULT_MONTHLY_SPREAD);
    }

    /** Spread injetavel: a mesa reprecifica risco sem recompilar (ver PricingProperties). */
    public DuplicataMercantilStrategy(BigDecimal monthlySpread) {
        this.monthlySpread = Objects.requireNonNull(monthlySpread, "monthlySpread");
    }

    @Override
    public ReceivableType receivableType() {
        return ReceivableType.DUPLICATA_MERCANTIL;
    }

    @Override
    public BigDecimal monthlySpread() {
        return monthlySpread;
    }
}
