package br.com.srm.creditengine.domain.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Premissa do SPEC.md: mes comercial de 30 dias e fracao de mes arredondada para cima. */
class TermCalculatorTest {

    private static final LocalDate OPERATION = LocalDate.of(2026, 1, 15);

    @ParameterizedTest(name = "{0} -> {1} mes(es)")
    @CsvSource({
            "2026-01-15, 0",   // mesmo dia: sem prazo
            "2026-02-14, 1",   // 30 dias corridos = 1 mes comercial exato
            "2026-02-15, 2",   // 31 dias: fracao de mes sobe para 2
            "2026-04-15, 3"    // 90 dias = 3 meses comerciais exatos
    })
    void convertsDueDateIntoWholeMonths(LocalDate dueDate, int expectedMonths) {
        assertThat(TermCalculator.termInMonths(OPERATION, dueDate)).isEqualTo(expectedMonths);
    }

    @Test
    @DisplayName("Um dia de prazo ja conta como um mes (fundo exposto ao mes iniciado)")
    void singleDayCountsAsOneMonth() {
        assertThat(TermCalculator.termInMonths(OPERATION, OPERATION.plusDays(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("Vencimento no passado e rejeitado")
    void pastDueDateIsRejected() {
        assertThatThrownBy(() -> TermCalculator.termInMonths(OPERATION, OPERATION.minusDays(1)))
                .isInstanceOf(InvalidPricingInputException.class);
    }
}
