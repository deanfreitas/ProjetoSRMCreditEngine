package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.domain.receivable.Receivable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Recebivel na resposta da API.
 *
 * <p>{@code version} e exposta de proposito: e a versao usada no optimistic locking e
 * permite ao cliente saber que o registro mudou desde a leitura.
 */
@Schema(name = "Receivable")
public record ReceivableView(
        UUID id,
        UUID assignorId,
        String type,
        MoneyView faceValue,
        LocalDate dueDate,
        String status,
        long version
) {

    public static ReceivableView of(Receivable receivable) {
        return new ReceivableView(
                receivable.id(),
                receivable.assignorId(),
                receivable.type().name(),
                MoneyView.of(receivable.faceValue()),
                receivable.dueDate(),
                receivable.status().name(),
                receivable.version());
    }
}
