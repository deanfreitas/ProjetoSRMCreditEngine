package br.com.srm.creditengine.application.statement;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.pricing.InvalidPricingInputException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Filtro do extrato de liquidacao: periodo, cedente e moeda (enunciado, item 4.1.6).
 *
 * <p>Paginacao e <b>server-side</b> e o tamanho de pagina tem teto: extrato sem limite e
 * como um relatorio de um ano derruba o banco em horario de mesa.
 *
 * @param from               inicio do periodo (inclusivo); nulo = sem limite inferior
 * @param to                 fim do periodo (exclusivo); nulo = sem limite superior
 * @param assignorId         cedente; nulo = todos
 * @param settlementCurrency moeda de pagamento; nula = todas
 */
public record StatementFilter(
        Instant from,
        Instant to,
        UUID assignorId,
        Currency settlementCurrency,
        int page,
        int size
) {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public StatementFilter {
        if (page < 0) {
            throw new InvalidPricingInputException("Pagina nao pode ser negativa: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPricingInputException(
                    "Tamanho de pagina deve estar entre 1 e %d: %d".formatted(MAX_PAGE_SIZE, size));
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidPricingInputException("Fim do periodo (%s) anterior ao inicio (%s)".formatted(to, from));
        }
    }

    public Optional<Instant> optionalFrom() {
        return Optional.ofNullable(from);
    }

    public Optional<Instant> optionalTo() {
        return Optional.ofNullable(to);
    }

    public int offset() {
        return page * size;
    }
}
