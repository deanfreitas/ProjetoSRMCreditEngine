package br.com.srm.creditengine.domain;

/**
 * Raiz das violacoes de regra de negocio. Existe para que a camada de aplicacao possa
 * traduzir erro de dominio em status HTTP semantico num unico ponto (exception handler),
 * em vez de espalhar try/catch por controller.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Codigo estavel para o cliente da API e para agregacao em log/metricas. */
    public abstract String errorCode();
}
