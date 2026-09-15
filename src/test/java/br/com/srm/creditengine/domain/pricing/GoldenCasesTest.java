package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Afericao obrigatoria do enunciado (secao 4.3).
 *
 * <p>Premissas fixas destes casos, independentes da configuracao de ambiente:
 * taxa base 1,00% a.m., prazo em meses inteiros, juros compostos mensais,
 * half-even com 2 casas apenas no resultado final e conversao cambial aplicada
 * sobre o valor presente em BRL <b>ja arredondado</b>.
 *
 * <p>Por isso o teste instancia o motor com taxa base fixa em vez de ler
 * {@code application.yml}: se alguem mudar a taxa do ambiente, a afericao continua
 * valida e o teste continua verdadeiro.
 */
class GoldenCasesTest {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 1, 15);
    private static final BigDecimal GOLDEN_BASE_RATE = new BigDecimal("0.01");

    private final PricingEngine engine = new PricingEngine(
            PricingStrategyRegistry.of(new DuplicataMercantilStrategy(), new ChequePreDatadoStrategy()),
            new FixedBaseRateProvider(GOLDEN_BASE_RATE));

    @Test
    @DisplayName("C1: duplicata R$ 100.000,00, 3 meses, BRL -> R$ 92.859,94")
    void c1DuplicataDomestica() {
        PricingInput input = PricingInput.domestic(
                Money.of("100000.00", Currency.BRL),
                ReceivableType.DUPLICATA_MERCANTIL,
                3,
                REFERENCE_DATE);

        PricingResult result = engine.price(input);

        assertThat(result.presentValue()).isEqualTo(Money.of("92859.94", Currency.BRL));
        assertThat(result.settlementAmount()).isEqualTo(Money.of("92859.94", Currency.BRL));
        assertThat(result.discount()).isEqualTo(Money.of("7140.06", Currency.BRL));
        assertThat(result.optionalFxRate()).isEmpty();
    }

    @Test
    @DisplayName("C2: cheque R$ 25.000,00, 2 meses, BRL -> R$ 23.337,77")
    void c2ChequeDomestico() {
        PricingInput input = PricingInput.domestic(
                Money.of("25000.00", Currency.BRL),
                ReceivableType.CHEQUE_PRE_DATADO,
                2,
                REFERENCE_DATE);

        PricingResult result = engine.price(input);

        assertThat(result.presentValue()).isEqualTo(Money.of("23337.77", Currency.BRL));
        assertThat(result.settlementAmount()).isEqualTo(Money.of("23337.77", Currency.BRL));
        assertThat(result.discount()).isEqualTo(Money.of("1662.23", Currency.BRL));
    }

    @Test
    @DisplayName("C3: duplicata R$ 100.000,00, 3 meses, pagamento em USD a 5,4321 -> US$ 17.094,67")
    void c3DuplicataCrossCurrency() {
        FxRate usdBrl = FxRate.of(Currency.USD, Currency.BRL, "5.4321",
                Instant.parse("2026-01-15T13:00:00Z"), "MANUAL");

        PricingInput input = PricingInput.crossCurrency(
                Money.of("100000.00", Currency.BRL),
                ReceivableType.DUPLICATA_MERCANTIL,
                3,
                Currency.USD,
                usdBrl,
                REFERENCE_DATE);

        PricingResult result = engine.price(input);

        assertThat(result.settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
        assertThat(result.presentValue()).isEqualTo(Money.of("92859.94", Currency.BRL));
        assertThat(result.discount()).isEqualTo(Money.of("7140.06", Currency.BRL));
        assertThat(result.optionalFxRate()).contains(usdBrl);
    }

    @Test
    @DisplayName("Invariante contabil: face = valor presente + desagio, nos tres casos")
    void faceValueEqualsPresentValuePlusDiscount() {
        var inputs = new PricingInput[]{
                PricingInput.domestic(Money.of("100000.00", Currency.BRL),
                        ReceivableType.DUPLICATA_MERCANTIL, 3, REFERENCE_DATE),
                PricingInput.domestic(Money.of("25000.00", Currency.BRL),
                        ReceivableType.CHEQUE_PRE_DATADO, 2, REFERENCE_DATE),
                PricingInput.crossCurrency(Money.of("100000.00", Currency.BRL),
                        ReceivableType.DUPLICATA_MERCANTIL, 3, Currency.USD,
                        FxRate.of(Currency.USD, Currency.BRL, "5.4321", Instant.now(), "MANUAL"),
                        REFERENCE_DATE)
        };

        for (PricingInput input : inputs) {
            PricingResult result = engine.price(input);
            assertThat(result.presentValue().add(result.discount()).rounded())
                    .isEqualTo(result.faceValue());
        }
    }
}
