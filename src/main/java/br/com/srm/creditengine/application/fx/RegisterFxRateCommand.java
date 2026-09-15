package br.com.srm.creditengine.application.fx;

import br.com.srm.creditengine.domain.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Atualizacao manual de cotacao.
 *
 * <p>{@code effectiveAt} e opcional: quando ausente, vale "agora". Manter o campo aberto
 * permite registrar a cotacao de fechamento do dia anterior sem reescrever historico.
 */
public record RegisterFxRateCommand(
        Currency baseCurrency,
        Currency quoteCurrency,
        BigDecimal rate,
        Instant effectiveAt,
        String source
) {

    public RegisterFxRateCommand {
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        Objects.requireNonNull(quoteCurrency, "quoteCurrency");
        Objects.requireNonNull(rate, "rate");
    }
}
