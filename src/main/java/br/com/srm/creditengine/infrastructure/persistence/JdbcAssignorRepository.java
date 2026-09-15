package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAssignorRepository implements AssignorRepository {

    private static final String INSERT = """
            INSERT INTO assignors (id, document, legal_name, created_at)
            VALUES (:id, :document, :legalName, :createdAt)
            """;

    private static final String SELECT = "SELECT id, document, legal_name, created_at FROM assignors ";

    private final JdbcClient jdbcClient;

    public JdbcAssignorRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Assignor save(Assignor assignor) {
        jdbcClient.sql(INSERT)
                .param("id", assignor.id())
                .param("document", assignor.document())
                .param("legalName", assignor.legalName())
                .param("createdAt", java.sql.Timestamp.from(assignor.createdAt()))
                .update();
        return assignor;
    }

    @Override
    public Optional<Assignor> findById(UUID id) {
        return jdbcClient.sql(SELECT + "WHERE id = :id")
                .param("id", id)
                .query(rowMapper())
                .optional();
    }

    @Override
    public Optional<Assignor> findByDocument(String document) {
        return jdbcClient.sql(SELECT + "WHERE document = :document")
                .param("document", document)
                .query(rowMapper())
                .optional();
    }

    private static RowMapper<Assignor> rowMapper() {
        return (rs, rowNum) -> new Assignor(
                rs.getObject("id", UUID.class),
                rs.getString("document"),
                rs.getString("legal_name"),
                rs.getTimestamp("created_at").toInstant());
    }
}
