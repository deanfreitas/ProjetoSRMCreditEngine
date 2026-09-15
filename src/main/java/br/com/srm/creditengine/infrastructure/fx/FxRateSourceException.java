package br.com.srm.creditengine.infrastructure.fx;

/**
 * Falha tecnica ao consultar o provedor externo de cotacao.
 *
 * <p>Fica na infraestrutura de proposito: o dominio nao precisa saber que existe HTTP,
 * timeout ou disjuntor. Quem traduz isso em linguagem de negocio - "nao da para liquidar
 * cross-currency agora" - e o {@link ResilientFxRateProvider}, que converte esta falha em
 * {@code FxRateUnavailableException} (503).
 */
public class FxRateSourceException extends RuntimeException {

    public FxRateSourceException(String message) {
        super(message);
    }

    public FxRateSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
