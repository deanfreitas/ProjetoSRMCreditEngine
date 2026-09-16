package br.com.srm.creditengine.application.assignor;

import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorAlreadyRegisteredException;
import br.com.srm.creditengine.domain.assignor.AssignorNotFoundException;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Cadastro de cedentes. Existe para que o recebivel e o extrato tenham contraparte
 * identificada - no Anexo A nao havia cedente nenhum, e o extrato por cedente pedido no
 * enunciado seria impossivel.
 */
@Service
public class AssignorService {

    private final AssignorRepository assignorRepository;
    private final Clock clock;

    public AssignorService(AssignorRepository assignorRepository, Clock clock) {
        this.assignorRepository = assignorRepository;
        this.clock = clock;
    }

    @Transactional
    public Assignor register(String document, String legalName) {
        // Checagem antes do insert para devolver erro de negocio claro; a unicidade real
        // continua sendo a do banco (duas requisicoes simultaneas nao passam pelo check).
        assignorRepository.findByDocument(document).ifPresent(existing -> {
            throw new AssignorAlreadyRegisteredException(document);
        });

        Assignor assignor = new Assignor(UUID.randomUUID(), document, legalName, clock.instant());
        try {
            return assignorRepository.save(assignor);
        } catch (DataIntegrityViolationException e) {
            throw new AssignorAlreadyRegisteredException(document);
        }
    }

    @Transactional(readOnly = true)
    public Assignor findById(UUID id) {
        return assignorRepository.findById(id)
                .orElseThrow(() -> new AssignorNotFoundException(id));
    }
}
