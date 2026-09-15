package br.com.srm.creditengine.domain.money;

import br.com.srm.creditengine.domain.DomainException;

/** Operacao aritmetica entre moedas diferentes: sempre bug, nunca dado do usuario. */
public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Operacao monetaria entre moedas distintas: esperado " + expected + ", recebido " + actual);
    }

    @Override
    public String errorCode() {
        return "CURRENCY_MISMATCH";
    }
}
