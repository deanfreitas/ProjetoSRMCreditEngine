package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrada completa e autocontida de uma precificacao.
 *
 * <p>Nada de I/O aqui: a cotacao de cambio e a data de referencia chegam <b>resolvidas</b>
 * pela camada de aplicacao. Isso mantem o motor deterministico e testavel sem mocks, e
 * garante que a liquidacao precifique com a taxa que sera auditada.
 *
 * @param faceValue          valor de face do titulo, na moeda de emissao (tipicamente BRL)
 * @param receivableType     define qual strategy precifica
 * @param termMonths         prazo em meses inteiros (ver TermCalculator para a conversao de datas)
 * @param settlementCurrency moeda em que o cedente recebe
 * @param fxRate             obrigatoria apenas quando a moeda de liquidacao difere da moeda de face
 * @param referenceDate      data da operacao; define qual taxa base vigente se aplica
 */
public record PricingInput(
        Money faceValue,
        ReceivableType receivableType,
        int termMonths,
        Currency settlementCurrency,
        FxRate fxRate,
        LocalDate referenceDate
) {

    public PricingInput {
        Objects.requireNonNull(faceValue, "faceValue");
        Objects.requireNonNull(receivableType, "receivableType");
        Objects.requireNonNull(settlementCurrency, "settlementCurrency");
        Objects.requireNonNull(referenceDate, "referenceDate");
        if (!faceValue.isPositive()) {
            throw new InvalidPricingInputException("Valor de face deve ser positivo: " + faceValue);
        }
        if (termMonths < 0) {
            throw new InvalidPricingInputException("Prazo em meses nao pode ser negativo: " + termMonths);
        }
    }

    public static PricingInput domestic(Money faceValue,
                                        ReceivableType receivableType,
                                        int termMonths,
                                        LocalDate referenceDate) {
        return new PricingInput(faceValue, receivableType, termMonths, faceValue.currency(), null, referenceDate);
    }

    public static PricingInput crossCurrency(Money faceValue,
                                             ReceivableType receivableType,
                                             int termMonths,
                                             Currency settlementCurrency,
                                             FxRate fxRate,
                                             LocalDate referenceDate) {
        Objects.requireNonNull(fxRate, "fxRate");
        return new PricingInput(faceValue, receivableType, termMonths, settlementCurrency, fxRate, referenceDate);
    }

    public boolean isCrossCurrency() {
        return settlementCurrency != faceValue.currency();
    }

    public Optional<FxRate> optionalFxRate() {
        return Optional.ofNullable(fxRate);
    }
}
