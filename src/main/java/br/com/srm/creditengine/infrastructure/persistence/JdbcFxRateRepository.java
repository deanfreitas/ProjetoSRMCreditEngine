package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.money.Currency;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcFxRateRepository implements FxRateRepository {

    private static final String INSERT = """
            INSERT INTO fx_rates (id, base_currency, quote_currency, rate, effective_at, source)
            VALUES (:id, :baseCurrency, :quoteCurrency, :rate, :effectiveAt, :source)
            """;

    /**
     * Cotacao vigente: a mais recente cuja vigencia <b>nao esta no futuro</b> em relacao ao
     * instante consultado. Usar simplesmente "a ultima inserida" permitiria precificar com
     * uma taxa agendada para amanha.
     */
    private static final String SELECT_LATEST = """
            SELECT base_currency, quote_currency, rate, effective_at, source
              FROM fx_rates
             WHERE base_currency = :baseCurrency
               AND quote_currency = :quoteCurrency
               AND effective_at <= :at
             ORDER BY effective_at DESC
             LIMIT 1
            """;

    private static final String SELECT_HISTORY = """
            SELECT base_currency, quote_currency, rate, effective_at, source
              FROM fx_rates
             WHERE base_currency = :baseCurrency
               AND quote_currency = :quoteCurrency
             ORDER BY effective_at DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    public JdbcFxRateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public FxRate save(FxRate rate) {
        jdbcClient.sql(INSERT)
                .param("id", UUID.randomUUID())
                .param("baseCurrency", rate.baseCurrency().name())
                .param("quoteCurrency", rate.quoteCurrency().name())
                .param("rate", rate.rate())
                .param("effectiveAt", Timestamp.from(rate.effectiveAt()))
                .param("source", rate.source())
                .update();
        return rate;
    }

    @Override
    public Optional<FxRate> findLatest(Currency baseCurrency, Currency quoteCurrency, Instant at) {
        return jdbcClient.sql(SELECT_LATEST)
                .param("baseCurrency", baseCurrency.name())
                .param("quoteCurrency", quoteCurrency.name())
                .param("at", Timestamp.from(at))
                .query(rowMapper())
                .optional();
    }

    @Override
    public List<FxRate> findHistory(Currency baseCurrency, Currency quoteCurrency, int limit) {
        return jdbcClient.sql(SELECT_HISTORY)
                .param("baseCurrency", baseCurrency.name())
                .param("quoteCurrency", quoteCurrency.name())
                .param("limit", limit)
                .query(rowMapper())
                .list();
    }

    private static RowMapper<FxRate> rowMapper() {
        return (rs, rowNum) -> new FxRate(
                Currency.valueOf(rs.getString("base_currency").trim()),
                Currency.valueOf(rs.getString("quote_currency").trim()),
                rs.getBigDecimal("rate"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getString("source"));
    }
}
