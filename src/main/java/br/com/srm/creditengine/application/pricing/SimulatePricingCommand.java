package br.com.srm.creditengine.application.pricing;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pedido de simulacao: precifica sem cadastrar nem liquidar nada.
 *
 * <p>E a operacao que o painel do operador chama a cada digito, por isso ela nao toca em
 * recebivel persistido. Continua usando o <b>mesmo</b> motor da liquidacao: simulacao que
 * calcula por um caminho e liquidacao por outro e como o operador descobre a divergencia
 * depois de fechar a operacao.
 */
public record SimulatePricingCommand(
        BigDecimal faceValue,
        Currency faceCurrency,
        ReceivableType type,
        LocalDate dueDate,
        Currency settlementCurrency
) {

    public SimulatePricingCommand {
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(faceCurrency, "faceCurrency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(settlementCurrency, "settlementCurrency");
    }

    public Money faceMoney() {
        return Money.of(faceValue, faceCurrency);
    }
}
