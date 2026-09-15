package br.com.srm.creditengine.domain.money;

/**
 * Moedas suportadas pelo fundo. Enum (e nao {@link java.util.Currency}) porque o
 * dominio precisa restringir explicitamente o conjunto operavel: adicionar uma moeda
 * e uma decisao de negocio, nao um dado de entrada livre.
 */
public enum Currency {

    BRL("R$"),
    USD("US$");

    private final String symbol;

    Currency(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
