package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.application.statement.SettlementStatementQuery;
import br.com.srm.creditengine.application.statement.StatementEntry;
import br.com.srm.creditengine.application.statement.StatementFilter;
import br.com.srm.creditengine.application.statement.StatementPage;
import br.com.srm.creditengine.application.statement.StatementTotal;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extrato analitico implementado via JPA/JPQL.
 */
@Repository
public class JpaSettlementStatementQuery implements SettlementStatementQuery {

    @PersistenceContext
    private final EntityManager entityManager;

    public JpaSettlementStatementQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementPage findBy(StatementFilter filter) {
        Criteria criteria = criteriaOf(filter);

        String selectPageJpql = """
                SELECT s.id, s.receivableId, s.assignorId,
                       a.document, a.legalName,
                       r.receivableType, r.dueDate,
                       s.termMonths,
                       s.faceValue, s.faceCurrency,
                       s.presentValue, s.discountAmount,
                       s.settlementAmount, s.settlementCurrency,
                       s.fxRate, s.settledAt
                  FROM SettlementJpaEntity s,
                       AssignorJpaEntity a,
                       ReceivableJpaEntity r
                 WHERE a.id = s.assignorId
                   AND r.id = s.receivableId
                """ + criteria.andWhere() + " ORDER BY s.settledAt DESC, s.id";

        TypedQuery<Object[]> pageQuery = entityManager.createQuery(selectPageJpql, Object[].class);
        criteria.params().forEach(pageQuery::setParameter);
        pageQuery.setFirstResult(filter.offset());
        pageQuery.setMaxResults(filter.size());

        List<StatementEntry> content = pageQuery.getResultList().stream()
                .map(JpaSettlementStatementQuery::toEntry)
                .toList();

        String selectCountJpql = "SELECT count(s) FROM SettlementJpaEntity s" + criteria.where();
        TypedQuery<Long> countQuery = entityManager.createQuery(selectCountJpql, Long.class);
        criteria.params().forEach(countQuery::setParameter);
        long totalElements = countQuery.getSingleResult();

        String selectTotalsJpql = """
                SELECT s.faceCurrency,
                       s.settlementCurrency,
                       count(s),
                       sum(s.faceValue),
                       sum(s.presentValue),
                       sum(s.discountAmount),
                       sum(s.settlementAmount)
                  FROM SettlementJpaEntity s
                """ + criteria.where() + " GROUP BY s.faceCurrency, s.settlementCurrency";

        TypedQuery<Object[]> totalsQuery = entityManager.createQuery(selectTotalsJpql, Object[].class);
        criteria.params().forEach(totalsQuery::setParameter);

        List<StatementTotal> totals = totalsQuery.getResultList().stream()
                .map(JpaSettlementStatementQuery::toTotal)
                .toList();

        return new StatementPage(content, filter.page(), filter.size(), totalElements, totals);
    }

    private static StatementEntry toEntry(Object[] row) {
        UUID id = (UUID) row[0];
        UUID receivableId = (UUID) row[1];
        UUID assignorId = (UUID) row[2];
        String document = (String) row[3];
        String legalName = (String) row[4];
        ReceivableType receivableType = (ReceivableType) row[5];
        LocalDate dueDate = (LocalDate) row[6];
        int termMonths = ((Number) row[7]).intValue();
        BigDecimal faceValue = (BigDecimal) row[8];
        Currency faceCurrency = (Currency) row[9];
        BigDecimal presentValue = (BigDecimal) row[10];
        BigDecimal discountAmount = (BigDecimal) row[11];
        BigDecimal settlementAmount = (BigDecimal) row[12];
        Currency settlementCurrency = (Currency) row[13];
        BigDecimal fxRate = (BigDecimal) row[14];
        Instant settledAt = (Instant) row[15];

        return new StatementEntry(
                id,
                receivableId,
                assignorId,
                document,
                legalName,
                receivableType,
                dueDate,
                termMonths,
                Money.of(faceValue, faceCurrency),
                Money.of(presentValue, faceCurrency),
                Money.of(discountAmount, faceCurrency),
                Money.of(settlementAmount, settlementCurrency),
                fxRate,
                settledAt);
    }

    private static StatementTotal toTotal(Object[] row) {
        Currency faceCurrency = (Currency) row[0];
        Currency settlementCurrency = (Currency) row[1];
        long count = ((Number) row[2]).longValue();
        BigDecimal totalFaceValue = (BigDecimal) row[3];
        BigDecimal totalPresentValue = (BigDecimal) row[4];
        BigDecimal totalDiscount = (BigDecimal) row[5];
        BigDecimal totalSettlementAmount = (BigDecimal) row[6];

        return new StatementTotal(
                count,
                Money.of(totalFaceValue, faceCurrency),
                Money.of(totalPresentValue, faceCurrency),
                Money.of(totalDiscount, faceCurrency),
                Money.of(totalSettlementAmount, settlementCurrency));
    }

    private static Criteria criteriaOf(StatementFilter filter) {
        List<String> predicates = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        filter.optionalFrom().ifPresent(from -> {
            predicates.add("s.settledAt >= :from");
            params.put("from", from);
        });
        filter.optionalTo().ifPresent(to -> {
            predicates.add("s.settledAt < :to");
            params.put("to", to);
        });
        if (filter.assignorId() != null) {
            predicates.add("s.assignorId = :assignorId");
            params.put("assignorId", filter.assignorId());
        }
        if (filter.settlementCurrency() != null) {
            predicates.add("s.settlementCurrency = :settlementCurrency");
            params.put("settlementCurrency", filter.settlementCurrency());
        }

        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String andWhere = predicates.isEmpty() ? "" : " AND " + String.join(" AND ", predicates);
        return new Criteria(where, andWhere, params);
    }

    private record Criteria(String where, String andWhere, Map<String, Object> params) {
    }
}
