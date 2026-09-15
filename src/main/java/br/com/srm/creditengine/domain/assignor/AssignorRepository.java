package br.com.srm.creditengine.domain.assignor;

import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia de cedentes. */
public interface AssignorRepository {

    Assignor save(Assignor assignor);

    Optional<Assignor> findById(UUID id);

    Optional<Assignor> findByDocument(String document);
}
