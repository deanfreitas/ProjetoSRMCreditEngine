package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.application.statement.SettlementStatementQuery;
import br.com.srm.creditengine.application.statement.StatementEntry;
import br.com.srm.creditengine.application.statement.StatementFilter;
import br.com.srm.creditengine.application.statement.StatementPage;
import br.com.srm.creditengine.application.statement.StatementTotal;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extrato analitico em SQL nativo.
 *
 * <p>Por que nao ORM aqui: o extrato precisa de tres coisas que ORM entrega mal -
 * projecao achatada com join em cedente e recebivel, agregacao por moeda e paginacao com
 * contagem total na mesma requisicao. Em ORM isso viraria N+1 ou uma query nativa
 * embrulhada em anotacao. O SQL abaixo casa com os indices criados na migration V1
 * ({@code ix_settlements_settled_at}, {@code ix_settlements_assignor_settled_at},
 * {@code ix_settlements_currency_settled_at}).
 *
 * <p>Os filtros sao montados dinamicamente, mas os <b>valores</b> nunca entram na string:
 * cada predicado usa parametro nomeado. Concatenar valor em SQL e o primeiro defeito do
 * Anexo A.
 */
@Repository
public class JdbcSettlementStatementQuery implements SettlementStatementQuery {

    private static final String SELECT_PAGE = """
            SELECT s.id, s.receivable_id, s.assignor_id,
                   a.document, a.legal_name,
                   r.receivable_type, r.due_date,
                   s.term_months,
                   s.face_value, s.face_currency,
                   s.present_value, s.discount_amount,
                   s.settlement_amount, s.settlement_currency,
                   s.fx_rate, s.settled_at
              FROM settlements s
              JOIN assignors a ON a.id = s.assignor_id
              JOIN receivables r ON r.id = s.receivable_id
            """;

    private static final String SELECT_COUNT = """
            SELECT count(*) FROM settlements s
            """;

    private static final String SELECT_TOTALS = """
            SELECT s.face_currency,
                   s.settlement_currency,
                   count(*)                  AS settlements,
                   sum(s.face_value)         AS total_face_value,
                   sum(s.present_value)      AS total_present_value,
                   sum(s.discount_amount)    AS total_discount,
                   sum(s.settlement_amount)  AS total_settlement_amount
              FROM settlements s
            """;

    private final JdbcClient jdbcClient;

    public JdbcSettlementStatementQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementPage findBy(StatementFilter filter) {
        Criteria criteria = criteriaOf(filter);

        // Ordenacao por (settled_at DESC, id): sem o desempate por id, duas liquidacoes no
        // mesmo instante poderiam aparecer nas duas paginas ou em nenhuma.
        List<StatementEntry> content = jdbcClient
                .sql(SELECT_PAGE + criteria.where() + " ORDER BY s.settled_at DESC, s.id LIMIT :limit OFFSET :offset")
                .params(criteria.paramsWith(Map.of("limit", filter.size(), "offset", filter.offset())))
                .query((rs, rowNum) -> toEntry(rs))
                .list();

        long totalElements = jdbcClient.sql(SELECT_COUNT + criteria.where())
                .params(criteria.params())
                .query(Long.class)
                .single();

        List<StatementTotal> totals = jdbcClient
                .sql(SELECT_TOTALS + criteria.where() + " GROUP BY s.face_currency, s.settlement_currency")
                .params(criteria.params())
                .query((rs, rowNum) -> {
                    Currency faceCurrency = currency(rs.getString("face_currency"));
                    Currency settlementCurrency = currency(rs.getString("settlement_currency"));
                    return new StatementTotal(
                            rs.getLong("settlements"),
                            Money.of(rs.getBigDecimal("total_face_value"), faceCurrency),
                            Money.of(rs.getBigDecimal("total_present_value"), faceCurrency),
                            Money.of(rs.getBigDecimal("total_discount"), faceCurrency),
                            Money.of(rs.getBigDecimal("total_settlement_amount"), settlementCurrency));
                })
                .list();

        return new StatementPage(content, filter.page(), filter.size(), totalElements, totals);
    }

    private static StatementEntry toEntry(ResultSet rs) throws SQLException {
        Currency faceCurrency = currency(rs.getString("face_currency"));
        Currency settlementCurrency = currency(rs.getString("settlement_currency"));

        return new StatementEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("receivable_id", UUID.class),
                rs.getObject("assignor_id", UUID.class),
                rs.getString("document"),
                rs.getString("legal_name"),
                ReceivableType.valueOf(rs.getString("receivable_type")),
                rs.getObject("due_date", LocalDate.class),
                rs.getInt("term_months"),
                Money.of(rs.getBigDecimal("face_value"), faceCurrency),
                Money.of(rs.getBigDecimal("present_value"), faceCurrency),
                Money.of(rs.getBigDecimal("discount_amount"), faceCurrency),
                Money.of(rs.getBigDecimal("settlement_amount"), settlementCurrency),
                rs.getBigDecimal("fx_rate"),
                rs.getTimestamp("settled_at").toInstant());
    }

    private static Currency currency(String value) {
        // CHAR(3) no Postgres volta com padding.
        return Currency.valueOf(value.trim());
    }

    private static Criteria criteriaOf(StatementFilter filter) {
        List<String> predicates = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        filter.optionalFrom().ifPresent(from -> {
            predicates.add("s.settled_at >= :from");
            params.put("from", Timestamp.from(from));
        });
        // Fim exclusivo: "periodo do dia 01 ao dia 31" com fim inclusivo perderia as
        // liquidacoes do dia 31 depois da meia-noite exata.
        filter.optionalTo().ifPresent(to -> {
            predicates.add("s.settled_at < :to");
            params.put("to", Timestamp.from(to));
        });
        if (filter.assignorId() != null) {
            predicates.add("s.assignor_id = :assignorId");
            params.put("assignorId", filter.assignorId());
        }
        if (filter.settlementCurrency() != null) {
            predicates.add("s.settlement_currency = :settlementCurrency");
            params.put("settlementCurrency", filter.settlementCurrency().name());
        }

        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        return new Criteria(where, params);
    }

    private record Criteria(String where, Map<String, Object> params) {

        Map<String, Object> paramsWith(Map<String, Object> extra) {
            Map<String, Object> merged = new HashMap<>(params);
            merged.putAll(extra);
            return merged;
        }
    }
}
