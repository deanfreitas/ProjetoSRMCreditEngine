package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bordas do motor: arredondamento, prazo, cambio e tipos nao suportados. */
class PricingEngineTest {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 1, 15);

    private final PricingEngine engine = new PricingEngine(
            PricingStrategyRegistry.of(new DuplicataMercantilStrategy(), new ChequePreDatadoStrategy()),
            new FixedBaseRateProvider(new BigDecimal("0.01")));

    @Test
    @DisplayName("Prazo zero: titulo vencido hoje nao sofre desagio")
    void zeroTermHasNoDiscount() {
        PricingResult result = engine.price(PricingInput.domestic(
                Money.of("1234.56", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL, 0, REFERENCE_DATE));

        assertThat(result.presentValue()).isEqualTo(Money.of("1234.56", Currency.BRL));
        assertThat(result.discount()).isEqualTo(Money.of("0.00", Currency.BRL));
    }

    @Test
    @DisplayName("Prazo longo (36 meses) nao perde precisao nem estoura escala")
    void longTermStaysExact() {
        PricingResult result = engine.price(PricingInput.domestic(
                Money.of("100000.00", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL, 36, REFERENCE_DATE));

        // 100000 / 1,025^36 = 41.109,37233... -> half-even 2 casas
        assertThat(result.presentValue()).isEqualTo(Money.of("41109.37", Currency.BRL));
        assertThat(result.presentValue().add(result.discount()).rounded()).isEqualTo(result.faceValue());
    }

    @Test
    @DisplayName("Valor de face de um centavo continua coerente (sem valor presente negativo)")
    void oneCentFaceValue() {
        PricingResult result = engine.price(PricingInput.domestic(
                Money.of("0.01", Currency.BRL), ReceivableType.CHEQUE_PRE_DATADO, 12, REFERENCE_DATE));

        assertThat(result.presentValue().isNegative()).isFalse();
        assertThat(result.presentValue()).isEqualTo(Money.of("0.01", Currency.BRL));
    }

    @Test
    @DisplayName("Spread do tipo e respeitado: cheque desconta mais que duplicata no mesmo prazo")
    void chequeIsDiscountedMoreThanDuplicata() {
        Money face = Money.of("50000.00", Currency.BRL);

        Money duplicata = engine.price(PricingInput.domestic(
                face, ReceivableType.DUPLICATA_MERCANTIL, 6, REFERENCE_DATE)).presentValue();
        Money cheque = engine.price(PricingInput.domestic(
                face, ReceivableType.CHEQUE_PRE_DATADO, 6, REFERENCE_DATE)).presentValue();

        assertThat(cheque).isLessThan(duplicata);
    }

    @Test
    @DisplayName("Taxas aplicadas ficam no resultado para auditoria")
    void resultCarriesAppliedRates() {
        PricingResult result = engine.price(PricingInput.domestic(
                Money.of("1000.00", Currency.BRL), ReceivableType.CHEQUE_PRE_DATADO, 1, REFERENCE_DATE));

        assertThat(result.monthlyBaseRate()).isEqualByComparingTo("0.01");
        assertThat(result.monthlySpread()).isEqualByComparingTo("0.025");
        assertThat(result.effectiveMonthlyRate()).isEqualByComparingTo("0.035");
        assertThat(result.termMonths()).isEqualTo(1);
    }

    @Nested
    @DisplayName("Arredondamento")
    class Rounding {

        @ParameterizedTest(name = "half-even: {0} em {1} mes(es) -> {2}")
        @CsvSource({
                // divisor 1,025 gera dizima; valores conferidos em calculo independente (bc, 30 casas)
                "1000.00, 1, 975.61",     // 975,6097560...
                "0.03, 1, 0.03",          // 0,0292682...
                "3.075, 1, 3.00",         // divisao exata: 3,0
                "0.128125, 1, 0.12",      // empate em 0,125 -> desce para o par
                "0.138375, 1, 0.14"       // empate em 0,135 -> sobe para o par
        })
        void appliesHalfEvenOnFinalResultOnly(String face, int termMonths, String expectedPresentValue) {
            PricingResult result = engine.price(PricingInput.domestic(
                    Money.of(face, Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL, termMonths, REFERENCE_DATE));

            assertThat(result.presentValue()).isEqualTo(Money.of(expectedPresentValue, Currency.BRL));
        }

        @Test
        @DisplayName("Empate vai para o vizinho par, nao sempre para cima (diferenca vs HALF_UP)")
        void tieGoesToEvenNeighbour() {
            // 0,128125 / 1,025 = 0,125 exato: HALF_UP daria 0,13; half-even devolve 0,12
            Money down = engine.price(PricingInput.domestic(
                    Money.of("0.128125", Currency.BRL),
                    ReceivableType.DUPLICATA_MERCANTIL, 1, REFERENCE_DATE)).presentValue();
            // 0,138375 / 1,025 = 0,135 exato: aqui o vizinho par e para cima
            Money up = engine.price(PricingInput.domestic(
                    Money.of("0.138375", Currency.BRL),
                    ReceivableType.DUPLICATA_MERCANTIL, 1, REFERENCE_DATE)).presentValue();

            assertThat(down.amount()).isEqualByComparingTo("0.12");
            assertThat(up.amount()).isEqualByComparingTo("0.14");
        }
    }

    @Nested
    @DisplayName("Cambio")
    class Fx {

        @Test
        @DisplayName("Cross-currency sem cotacao resolvida falha em vez de assumir 1:1")
        void crossCurrencyWithoutRateFails() {
            PricingInput input = new PricingInput(
                    Money.of("100000.00", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL,
                    3, Currency.USD, null, REFERENCE_DATE);

            assertThatThrownBy(() -> engine.price(input))
                    .isInstanceOf(MissingFxRateException.class)
                    .hasMessageContaining("BRL");
        }

        @Test
        @DisplayName("Direcao da cotacao: titulo em USD pago em BRL multiplica pela taxa")
        void convertsInTheOppositeDirection() {
            FxRate usdBrl = FxRate.of(Currency.USD, Currency.BRL, "5.4321", Instant.now(), "MANUAL");

            PricingResult result = engine.price(PricingInput.crossCurrency(
                    Money.of("1000.00", Currency.USD), ReceivableType.DUPLICATA_MERCANTIL,
                    1, Currency.BRL, usdBrl, REFERENCE_DATE));

            // 1000 / 1,025 = 975,61 (USD) -> * 5,4321 = 5.298,88 (BRL)
            assertThat(result.presentValue()).isEqualTo(Money.of("975.61", Currency.USD));
            assertThat(result.settlementAmount().currency()).isEqualTo(Currency.BRL);
            assertThat(result.settlementAmount().amount()).isEqualByComparingTo(
                    new BigDecimal("975.61").multiply(new BigDecimal("5.4321"))
                            .setScale(2, java.math.RoundingMode.HALF_EVEN));
        }

        @Test
        @DisplayName("Conversao usa o valor presente ja arredondado (premissa dos golden cases)")
        void convertsRoundedPresentValue() {
            FxRate usdBrl = FxRate.of(Currency.USD, Currency.BRL, "5.4321", Instant.now(), "MANUAL");

            PricingResult result = engine.price(PricingInput.crossCurrency(
                    Money.of("100000.00", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL,
                    3, Currency.USD, usdBrl, REFERENCE_DATE));

            BigDecimal manual = new BigDecimal("92859.94")
                    .divide(new BigDecimal("5.4321"), 2, java.math.RoundingMode.HALF_EVEN);
            assertThat(result.settlementAmount().amount()).isEqualByComparingTo(manual);
        }
    }

    @Nested
    @DisplayName("Entradas invalidas")
    class InvalidInput {

        @Test
        @DisplayName("Valor de face zero e rejeitado na construcao da entrada")
        void zeroFaceValueIsRejected() {
            assertThatThrownBy(() -> PricingInput.domestic(
                    Money.of("0.00", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL, 3, REFERENCE_DATE))
                    .isInstanceOf(InvalidPricingInputException.class);
        }

        @Test
        @DisplayName("Prazo negativo e rejeitado")
        void negativeTermIsRejected() {
            assertThatThrownBy(() -> PricingInput.domestic(
                    Money.of("100.00", Currency.BRL), ReceivableType.DUPLICATA_MERCANTIL, -1, REFERENCE_DATE))
                    .isInstanceOf(InvalidPricingInputException.class);
        }

        @Test
        @DisplayName("Tipo sem strategy registrada falha alto, sem spread default")
        void unsupportedTypeFails() {
            PricingEngine engineWithoutCheque = new PricingEngine(
                    PricingStrategyRegistry.of(new DuplicataMercantilStrategy()),
                    new FixedBaseRateProvider(new BigDecimal("0.01")));

            assertThatThrownBy(() -> engineWithoutCheque.price(PricingInput.domestic(
                    Money.of("100.00", Currency.BRL), ReceivableType.CHEQUE_PRE_DATADO, 1, REFERENCE_DATE)))
                    .isInstanceOf(UnsupportedReceivableTypeException.class);
        }

        @Test
        @DisplayName("Cotacao com moedas iguais ou taxa nao positiva e invalida")
        void invalidFxRateIsRejected() {
            assertThatThrownBy(() -> FxRate.of(Currency.BRL, Currency.BRL, "1", Instant.now(), "MANUAL"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> FxRate.of(Currency.USD, Currency.BRL, "0", Instant.now(), "MANUAL"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Duas strategies para o mesmo tipo derrubam a inicializacao")
        void duplicatedStrategyFailsFast() {
            assertThatThrownBy(() -> PricingStrategyRegistry.of(
                    new DuplicataMercantilStrategy(), new DuplicataMercantilStrategy()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
