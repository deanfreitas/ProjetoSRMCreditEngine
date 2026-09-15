package br.com.srm.creditengine.domain.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Valor monetario imutavel: quantia + moeda, sempre em {@link BigDecimal}.
 *
 * <p>Nao existe construtor a partir de {@code double}/{@code float} de proposito:
 * ponto flutuante binario nao representa 0,01 exatamente e o erro aparece como centavo
 * perdido na liquidacao.
 *
 * <p>A quantia <b>nao</b> e arredondada na construcao: valores intermediarios do motor
 * circulam com precisao plena e o arredondamento e aplicado explicitamente via
 * {@link #rounded()} no resultado final.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /** Fabrica textual: evita literais {@code double} em testes e em massa de dados. */
    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO.setScale(RoundingPolicy.MONETARY_SCALE), currency);
    }

    public Money rounded() {
        return new Money(RoundingPolicy.roundMonetary(amount), currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount, RoundingPolicy.CALCULATION), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount, RoundingPolicy.CALCULATION), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * Compara por valor numerico, ignorando escala: {@code 10.00} e igual a {@code 10}.
     * {@code equals} do record compara escala, por isso as assercoes de teste usam este metodo.
     */
    public boolean isEqualTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return currency.symbol() + " " + RoundingPolicy.roundMonetary(amount).toPlainString();
    }
}
