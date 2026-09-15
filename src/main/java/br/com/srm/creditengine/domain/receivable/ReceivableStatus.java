package br.com.srm.creditengine.domain.receivable;

/** Ciclo de vida do recebivel dentro do fundo. */
public enum ReceivableStatus {

    /** Cadastrado e elegivel a liquidacao. */
    PENDING,

    /** Ja liquidado: estado terminal. */
    SETTLED,

    /** Cancelado antes da liquidacao. */
    CANCELLED
}
