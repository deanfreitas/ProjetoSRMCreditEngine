package br.com.srm.creditengine.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("Soma de centavos e exata (o mesmo calculo erra com double)")
    void centsAddUpExactly() {
        Money total = Money.of("0.10", Currency.BRL)
                .add(Money.of("0.20", Currency.BRL));

        assertThat(total.amount()).isEqualByComparingTo("0.30");
        // demonstracao do anti-padrao: 0.1 + 0.2 em binario nao da 0.3
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
    }

    @Test
    @DisplayName("Aritmetica entre moedas diferentes falha explicitamente")
    void mixingCurrenciesFails() {
        assertThatThrownBy(() -> Money.of("1.00", Currency.BRL).add(Money.of("1.00", Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("Construcao nao arredonda; rounded() aplica half-even com 2 casas")
    void roundingIsExplicit() {
        Money raw = Money.of(new BigDecimal("1.005"), Currency.BRL);

        assertThat(raw.amount()).isEqualByComparingTo("1.005");
        assertThat(raw.rounded().amount()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("Comparacao por valor ignora escala")
    void comparisonIgnoresScale() {
        assertThat(Money.of("10", Currency.BRL).isEqualTo(Money.of("10.00", Currency.BRL))).isTrue();
        assertThat(Money.of("10", Currency.BRL)).isNotEqualTo(Money.of("10.00", Currency.BRL));
    }
}
