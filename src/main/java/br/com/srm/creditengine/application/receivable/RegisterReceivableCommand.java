package br.com.srm.creditengine.application.receivable;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de cadastro de recebivel.
 *
 * <p>O valor de face chega como {@link BigDecimal} ja convertido pela camada HTTP a partir
 * de <b>string</b>: numero JSON lido por cliente JavaScript vira double e perde centavo.
 */
public record RegisterReceivableCommand(
        UUID assignorId,
        ReceivableType type,
        BigDecimal faceValue,
        Currency faceCurrency,
        LocalDate dueDate
) {

    public RegisterReceivableCommand {
        Objects.requireNonNull(assignorId, "assignorId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(faceCurrency, "faceCurrency");
        Objects.requireNonNull(dueDate, "dueDate");
    }

    public Money faceMoney() {
        return Money.of(faceValue, faceCurrency);
    }
}
