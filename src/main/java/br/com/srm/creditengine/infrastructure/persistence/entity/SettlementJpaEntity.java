package br.com.srm.creditengine.infrastructure.persistence.entity;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.settlement.Settlement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlements")
public class SettlementJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "receivable_id", nullable = false, unique = true)
    private UUID receivableId;

    @Column(name = "assignor_id", nullable = false)
    private UUID assignorId;

    @Column(name = "idempotency_key", nullable = false, length = 120, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "monthly_base_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal monthlyBaseRate;

    @Column(name = "monthly_spread", nullable = false, precision = 19, scale = 6)
    private BigDecimal monthlySpread;

    @Column(name = "face_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal faceValue;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "face_currency", nullable = false, length = 3)
    private Currency faceCurrency;

    @Column(name = "present_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal presentValue;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "settlement_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal settlementAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "settlement_currency", nullable = false, length = 3)
    private Currency settlementCurrency;

    @Column(name = "fx_rate", precision = 19, scale = 6)
    private BigDecimal fxRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fx_base_currency", length = 3)
    private Currency fxBaseCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fx_quote_currency", length = 3)
    private Currency fxQuoteCurrency;

    @Column(name = "fx_rate_effective_at")
    private Instant fxRateEffectiveAt;

    @Column(name = "fx_rate_source", length = 40)
    private String fxRateSource;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    public SettlementJpaEntity() {
    }

    public static SettlementJpaEntity fromDomain(Settlement settlement) {
        SettlementJpaEntity entity = new SettlementJpaEntity();
        entity.setId(settlement.id());
        entity.setReceivableId(settlement.receivableId());
        entity.setAssignorId(settlement.assignorId());
        entity.setIdempotencyKey(settlement.idempotencyKey());
        entity.setRequestFingerprint(settlement.requestFingerprint());
        entity.setTermMonths(settlement.termMonths());
        entity.setMonthlyBaseRate(settlement.monthlyBaseRate());
        entity.setMonthlySpread(settlement.monthlySpread());
        entity.setFaceValue(settlement.faceValue().rounded().amount());
        entity.setFaceCurrency(settlement.faceValue().currency());
        entity.setPresentValue(settlement.presentValue().rounded().amount());
        entity.setDiscountAmount(settlement.discount().rounded().amount());
        entity.setSettlementAmount(settlement.settlementAmount().rounded().amount());
        entity.setSettlementCurrency(settlement.settlementAmount().currency());

        settlement.optionalFxRate().ifPresent(rate -> {
            entity.setFxRate(rate.rate());
            entity.setFxBaseCurrency(rate.baseCurrency());
            entity.setFxQuoteCurrency(rate.quoteCurrency());
            entity.setFxRateEffectiveAt(rate.effectiveAt());
            entity.setFxRateSource(rate.source());
        });

        entity.setSettledAt(settlement.settledAt());
        return entity;
    }

    public Settlement toDomain() {
        FxRate rate = null;
        if (fxRate != null && fxBaseCurrency != null && fxQuoteCurrency != null && fxRateEffectiveAt != null) {
            rate = new FxRate(fxBaseCurrency, fxQuoteCurrency, fxRate, fxRateEffectiveAt, fxRateSource);
        }

        return new Settlement(
                id,
                receivableId,
                assignorId,
                idempotencyKey,
                requestFingerprint,
                termMonths,
                monthlyBaseRate,
                monthlySpread,
                Money.of(faceValue, faceCurrency),
                Money.of(presentValue, faceCurrency),
                Money.of(discountAmount, faceCurrency),
                Money.of(settlementAmount, settlementCurrency),
                rate,
                settledAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReceivableId() {
        return receivableId;
    }

    public void setReceivableId(UUID receivableId) {
        this.receivableId = receivableId;
    }

    public UUID getAssignorId() {
        return assignorId;
    }

    public void setAssignorId(UUID assignorId) {
        this.assignorId = assignorId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public void setTermMonths(int termMonths) {
        this.termMonths = termMonths;
    }

    public BigDecimal getMonthlyBaseRate() {
        return monthlyBaseRate;
    }

    public void setMonthlyBaseRate(BigDecimal monthlyBaseRate) {
        this.monthlyBaseRate = monthlyBaseRate;
    }

    public BigDecimal getMonthlySpread() {
        return monthlySpread;
    }

    public void setMonthlySpread(BigDecimal monthlySpread) {
        this.monthlySpread = monthlySpread;
    }

    public BigDecimal getFaceValue() {
        return faceValue;
    }

    public void setFaceValue(BigDecimal faceValue) {
        this.faceValue = faceValue;
    }

    public Currency getFaceCurrency() {
        return faceCurrency;
    }

    public void setFaceCurrency(Currency faceCurrency) {
        this.faceCurrency = faceCurrency;
    }

    public BigDecimal getPresentValue() {
        return presentValue;
    }

    public void setPresentValue(BigDecimal presentValue) {
        this.presentValue = presentValue;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getSettlementAmount() {
        return settlementAmount;
    }

    public void setSettlementAmount(BigDecimal settlementAmount) {
        this.settlementAmount = settlementAmount;
    }

    public Currency getSettlementCurrency() {
        return settlementCurrency;
    }

    public void setSettlementCurrency(Currency settlementCurrency) {
        this.settlementCurrency = settlementCurrency;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public void setFxRate(BigDecimal fxRate) {
        this.fxRate = fxRate;
    }

    public Currency getFxBaseCurrency() {
        return fxBaseCurrency;
    }

    public void setFxBaseCurrency(Currency fxBaseCurrency) {
        this.fxBaseCurrency = fxBaseCurrency;
    }

    public Currency getFxQuoteCurrency() {
        return fxQuoteCurrency;
    }

    public void setFxQuoteCurrency(Currency fxQuoteCurrency) {
        this.fxQuoteCurrency = fxQuoteCurrency;
    }

    public Instant getFxRateEffectiveAt() {
        return fxRateEffectiveAt;
    }

    public void setFxRateEffectiveAt(Instant fxRateEffectiveAt) {
        this.fxRateEffectiveAt = fxRateEffectiveAt;
    }

    public String getFxRateSource() {
        return fxRateSource;
    }

    public void setFxRateSource(String fxRateSource) {
        this.fxRateSource = fxRateSource;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
