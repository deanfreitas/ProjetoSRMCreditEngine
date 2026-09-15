package br.com.srm.creditengine.domain.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Politica unica de precisao numerica do sistema (ver SPEC.md).
 *
 * <p>Regra: todo o encadeamento de calculo roda em {@link MathContext#DECIMAL128}
 * (34 digitos significativos) e o arredondamento monetario acontece <b>uma unica vez</b>,
 * no resultado final, com HALF_EVEN e 2 casas. Arredondar em etapas intermediarias
 * introduz erro acumulado; arredondar com HALF_UP enviesa o resultado a favor de uma
 * das pontas ao longo de milhares de liquidacoes.
 */
public final class RoundingPolicy {

    /** Contexto dos calculos intermediarios: precisao alta, sem arredondamento monetario. */
    public static final MathContext CALCULATION = MathContext.DECIMAL128;

    /** Casas decimais de qualquer valor monetario persistido ou exposto pela API. */
    public static final int MONETARY_SCALE = 2;

    /** Banker's rounding: exigido pelos golden cases e neutro no agregado. */
    public static final RoundingMode MONETARY_ROUNDING = RoundingMode.HALF_EVEN;

    /** Casas decimais de taxas (cambio e juros): 6 cobre BRL/USD e taxas ao mes. */
    public static final int RATE_SCALE = 6;

    private RoundingPolicy() {
    }

    public static BigDecimal roundMonetary(BigDecimal value) {
        return value.setScale(MONETARY_SCALE, MONETARY_ROUNDING);
    }

    public static BigDecimal roundRate(BigDecimal value) {
        return value.setScale(RATE_SCALE, MONETARY_ROUNDING);
    }
}
