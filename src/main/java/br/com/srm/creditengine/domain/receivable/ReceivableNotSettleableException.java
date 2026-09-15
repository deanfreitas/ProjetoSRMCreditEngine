package br.com.srm.creditengine.domain.receivable;

import br.com.srm.creditengine.domain.DomainException;

import java.util.UUID;

/** Tentativa de liquidar recebivel que nao esta elegivel (ja liquidado ou cancelado). */
public class ReceivableNotSettleableException extends DomainException {

    private final UUID receivableId;
    private final ReceivableStatus status;

    public ReceivableNotSettleableException(UUID receivableId, ReceivableStatus status) {
        super("Recebivel %s nao pode ser liquidado no estado %s".formatted(receivableId, status));
        this.receivableId = receivableId;
        this.status = status;
    }

    public UUID receivableId() {
        return receivableId;
    }

    public ReceivableStatus status() {
        return status;
    }

    @Override
    public String errorCode() {
        return "RECEIVABLE_NOT_SETTLEABLE";
    }
}
