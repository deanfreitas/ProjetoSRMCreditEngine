package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.settlement.Settlement;
import br.com.srm.creditengine.domain.settlement.SettlementRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter JDBC do registro de liquidacao. Apenas INSERT e SELECT: nao existe update nem
 * delete no codigo, e o banco tambem os rejeita por trigger.
 */
@Repository
public class JdbcSettlementRepository implements SettlementRepository {

    private static final String COLUMNS = """
            id, receivable_id, assignor_id, idempotency_key, request_fingerprint,
            term_months, monthly_base_rate, monthly_spread,
            face_value, face_currency, present_value, discount_amount,
            settlement_amount, settlement_currency,
            fx_rate, fx_base_currency, fx_quote_currency,
            fx_rate_effective_at, fx_rate_source, settled_at
            """;

    private static final String INSERT = """
            INSERT INTO settlements (
                id, receivable_id, assignor_id, idempotency_key, request_fingerprint,
                term_months, monthly_base_rate, monthly_spread,
                face_value, face_currency, present_value, discount_amount,
                settlement_amount, settlement_currency,
                fx_rate, fx_base_currency, fx_quote_currency,
                fx_rate_effective_at, fx_rate_source, settled_at
            ) VALUES (
                :id, :receivableId, :assignorId, :idempotencyKey, :requestFingerprint,
                :termMonths, :monthlyBaseRate, :monthlySpread,
                :faceValue, :faceCurrency, :presentValue, :discountAmount,
                :settlementAmount, :settlementCurrency,
                :fxRate, :fxBaseCurrency, :fxQuoteCurrency,
                :fxRateEffectiveAt, :fxRateSource, :settledAt
            )
            """;

    private final JdbcClient jdbcClient;

    public JdbcSettlementRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Settlement save(Settlement settlement) {
        Optional<FxRate> fxRate = settlement.optionalFxRate();

        jdbcClient.sql(INSERT)
                .param("id", settlement.id())
                .param("receivableId", settlement.receivableId())
                .param("assignorId", settlement.assignorId())
                .param("idempotencyKey", settlement.idempotencyKey())
                .param("requestFingerprint", settlement.requestFingerprint())
                .param("termMonths", settlement.termMonths())
                .param("monthlyBaseRate", settlement.monthlyBaseRate())
                .param("monthlySpread", settlement.monthlySpread())
                .param("faceValue", settlement.faceValue().amount())
                .param("faceCurrency", settlement.faceValue().currency().name())
                .param("presentValue", settlement.presentValue().amount())
                .param("discountAmount", settlement.discount().amount())
                .param("settlementAmount", settlement.settlementAmount().amount())
                .param("settlementCurrency", settlement.settlementAmount().currency().name())
                .param("fxRate", fxRate.map(FxRate::rate).orElse(null))
                .param("fxBaseCurrency", fxRate.map(rate -> rate.baseCurrency().name()).orElse(null))
                .param("fxQuoteCurrency", fxRate.map(rate -> rate.quoteCurrency().name()).orElse(null))
                .param("fxRateEffectiveAt", fxRate.map(rate -> java.sql.Timestamp.from(rate.effectiveAt())).orElse(null))
                .param("fxRateSource", fxRate.map(FxRate::source).orElse(null))
                .param("settledAt", java.sql.Timestamp.from(settlement.settledAt()))
                .update();

        return settlement;
    }

    @Override
    public Optional<Settlement> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM settlements WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(settlementRowMapper())
                .optional();
    }

    @Override
    public Optional<Settlement> findByReceivableId(UUID receivableId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM settlements WHERE receivable_id = :receivableId")
                .param("receivableId", receivableId)
                .query(settlementRowMapper())
                .optional();
    }

    static RowMapper<Settlement> settlementRowMapper() {
        return (rs, rowNum) -> toSettlement(rs);
    }

    private static Settlement toSettlement(ResultSet rs) throws SQLException {
        Currency faceCurrency = Currency.valueOf(rs.getString("face_currency").trim());
        Currency settlementCurrency = Currency.valueOf(rs.getString("settlement_currency").trim());

        // O par da cotacao e lido do proprio registro, nao inferido das moedas da operacao:
        // um titulo em USD pago em BRL inverteria a direcao e a auditoria ficaria ambigua.
        BigDecimal rate = rs.getBigDecimal("fx_rate");
        FxRate fxRate = rate == null ? null : new FxRate(
                Currency.valueOf(rs.getString("fx_base_currency").trim()),
                Currency.valueOf(rs.getString("fx_quote_currency").trim()),
                rate,
                rs.getTimestamp("fx_rate_effective_at").toInstant(),
                rs.getString("fx_rate_source"));

        return new Settlement(
                rs.getObject("id", UUID.class),
                rs.getObject("receivable_id", UUID.class),
                rs.getObject("assignor_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint").trim(),
                rs.getInt("term_months"),
                rs.getBigDecimal("monthly_base_rate"),
                rs.getBigDecimal("monthly_spread"),
                Money.of(rs.getBigDecimal("face_value"), faceCurrency),
                Money.of(rs.getBigDecimal("present_value"), faceCurrency),
                Money.of(rs.getBigDecimal("discount_amount"), faceCurrency),
                Money.of(rs.getBigDecimal("settlement_amount"), settlementCurrency),
                fxRate,
                toInstant(rs));
    }

    private static Instant toInstant(ResultSet rs) throws SQLException {
        return rs.getTimestamp("settled_at").toInstant();
    }
}
