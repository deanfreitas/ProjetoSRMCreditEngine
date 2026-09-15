package br.com.srm.creditengine.domain.receivable;

import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Recebivel adquirido pelo fundo.
 *
 * <p>Imutavel: {@link #version} e a versao lida do banco e viaja junto para que a
 * liquidacao possa gravar com optimistic locking. Mudanca de estado devolve nova
 * instancia ({@link #settled()}), nunca altera a existente.
 */
public record Receivable(
        UUID id,
        UUID assignorId,
        ReceivableType type,
        Money faceValue,
        LocalDate dueDate,
        ReceivableStatus status,
        long version
) {

    public Receivable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assignorId, "assignorId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(status, "status");
        if (!faceValue.isPositive()) {
            throw new IllegalArgumentException("Valor de face deve ser positivo: " + faceValue);
        }
    }

    public boolean isSettleable() {
        return status == ReceivableStatus.PENDING;
    }

    /**
     * Transicao para liquidado. A regra de "so liquida uma vez" e verificada aqui
     * (dominio), reforcada por optimistic locking e, no limite, pela unicidade no banco -
     * tres camadas porque o custo de liquidar duas vezes e pagar duas vezes.
     */
    public Receivable settled() {
        if (!isSettleable()) {
            throw new ReceivableNotSettleableException(id, status);
        }
        return new Receivable(id, assignorId, type, faceValue, dueDate, ReceivableStatus.SETTLED, version);
    }
}
