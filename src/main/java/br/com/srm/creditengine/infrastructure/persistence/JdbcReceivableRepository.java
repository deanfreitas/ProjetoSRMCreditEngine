package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter JDBC de recebiveis. Todo SQL e parametrizado - concatenar id em string, como no
 * Anexo A, e SQL injection com autenticacao de graca.
 */
@Repository
public class JdbcReceivableRepository implements ReceivableRepository {

    private static final String SELECT_BY_ID = """
            SELECT id, assignor_id, receivable_type, face_value, face_currency,
                   due_date, status, version
              FROM receivables
             WHERE id = :id
            """;

    private static final String INSERT = """
            INSERT INTO receivables (id, assignor_id, receivable_type, face_value,
                                     face_currency, due_date, status, version)
            VALUES (:id, :assignorId, :receivableType, :faceValue,
                    :faceCurrency, :dueDate, :status, :version)
            """;

    /**
     * Optimistic locking explicito: a versao lida entra na clausula WHERE. Zero linhas
     * afetadas significa que outra transacao liquidou primeiro. O filtro por status
     * 'PENDING' e cinto de seguranca redundante - e barato e evita depender so da versao.
     */
    private static final String SETTLE = """
            UPDATE receivables
               SET status = 'SETTLED',
                   version = version + 1
             WHERE id = :id
               AND version = :version
               AND status = 'PENDING'
            """;

    private final JdbcClient jdbcClient;

    public JdbcReceivableRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Receivable> findById(UUID id) {
        return jdbcClient.sql(SELECT_BY_ID)
                .param("id", id)
                .query((rs, rowNum) -> new Receivable(
                        rs.getObject("id", UUID.class),
                        rs.getObject("assignor_id", UUID.class),
                        ReceivableType.valueOf(rs.getString("receivable_type")),
                        Money.of(rs.getBigDecimal("face_value"),
                                Currency.valueOf(rs.getString("face_currency").trim())),
                        rs.getObject("due_date", java.time.LocalDate.class),
                        ReceivableStatus.valueOf(rs.getString("status")),
                        rs.getLong("version")))
                .optional();
    }

    @Override
    public Receivable save(Receivable receivable) {
        jdbcClient.sql(INSERT)
                .param("id", receivable.id())
                .param("assignorId", receivable.assignorId())
                .param("receivableType", receivable.type().name())
                .param("faceValue", receivable.faceValue().rounded().amount())
                .param("faceCurrency", receivable.faceValue().currency().name())
                .param("dueDate", receivable.dueDate())
                .param("status", receivable.status().name())
                .param("version", receivable.version())
                .update();
        return receivable;
    }

    @Override
    public void settle(Receivable receivable) {
        int affected = jdbcClient.sql(SETTLE)
                .param("id", receivable.id())
                .param("version", receivable.version())
                .update();

        if (affected == 0) {
            throw new ConcurrentSettlementException(receivable.id(), null);
        }
    }
}
