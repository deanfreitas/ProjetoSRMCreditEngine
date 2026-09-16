package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import br.com.srm.creditengine.infrastructure.persistence.entity.AssignorJpaEntity;
import br.com.srm.creditengine.infrastructure.persistence.jpa.SpringDataAssignorRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaAssignorRepository implements AssignorRepository {

    private final SpringDataAssignorRepository springDataRepository;

    public JpaAssignorRepository(SpringDataAssignorRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public Assignor save(Assignor assignor) {
        AssignorJpaEntity saved = springDataRepository.saveAndFlush(AssignorJpaEntity.fromDomain(assignor));
        return saved.toDomain();
    }

    @Override
    public Optional<Assignor> findById(UUID id) {
        return springDataRepository.findById(id).map(AssignorJpaEntity::toDomain);
    }

    @Override
    public Optional<Assignor> findByDocument(String document) {
        return springDataRepository.findByDocument(document).map(AssignorJpaEntity::toDomain);
    }
}
