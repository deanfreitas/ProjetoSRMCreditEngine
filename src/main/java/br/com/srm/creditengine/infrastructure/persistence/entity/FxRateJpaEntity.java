package br.com.srm.creditengine.infrastructure.persistence.entity;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.money.Currency;
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
@Table(name = "fx_rates")
public class FxRateJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, length = 3)
    private Currency baseCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, length = 3)
    private Currency quoteCurrency;

    @Column(name = "rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "source", nullable = false, length = 40)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public FxRateJpaEntity() {
    }

    public FxRateJpaEntity(UUID id, Currency baseCurrency, Currency quoteCurrency,
                           BigDecimal rate, Instant effectiveAt, String source) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.effectiveAt = effectiveAt;
        this.source = source;
    }

    public static FxRateJpaEntity fromDomain(FxRate rate) {
        return new FxRateJpaEntity(
                UUID.randomUUID(),
                rate.baseCurrency(),
                rate.quoteCurrency(),
                rate.rate(),
                rate.effectiveAt(),
                rate.source()
        );
    }

    public FxRate toDomain() {
        return new FxRate(baseCurrency, quoteCurrency, rate, effectiveAt, source);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Currency getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(Currency baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public Currency getQuoteCurrency() {
        return quoteCurrency;
    }

    public void setQuoteCurrency(Currency quoteCurrency) {
        this.quoteCurrency = quoteCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
