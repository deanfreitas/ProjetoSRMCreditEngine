package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.domain.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Valor monetario na resposta da API.
 *
 * <p>{@code amount} sai como <b>string</b>: JSON number lido por cliente JavaScript vira
 * {@code double} e perde centavo - o mesmo defeito do Anexo A, so que no outro lado do
 * fio. Quem consome converte para o tipo decimal da sua linguagem.
 */
@Schema(name = "Money", description = "Valor monetario com moeda; quantia em string para preservar precisao decimal")
public record MoneyView(
        @Schema(example = "92859.94") String amount,
        @Schema(example = "BRL") String currency
) {

    public static MoneyView of(Money money) {
        return new MoneyView(money.rounded().amount().toPlainString(), money.currency().name());
    }
}
