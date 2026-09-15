package br.com.srm.creditengine.application.receivable;

import br.com.srm.creditengine.domain.assignor.AssignorNotFoundException;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import br.com.srm.creditengine.domain.pricing.InvalidPricingInputException;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableNotFoundException;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Cadastro e consulta de recebiveis. */
@Service
public class ReceivableService {

    private final ReceivableRepository receivableRepository;
    private final AssignorRepository assignorRepository;
    private final Clock clock;

    public ReceivableService(ReceivableRepository receivableRepository,
                             AssignorRepository assignorRepository,
                             Clock clock) {
        this.receivableRepository = receivableRepository;
        this.assignorRepository = assignorRepository;
        this.clock = clock;
    }

    @Transactional
    public Receivable register(RegisterReceivableCommand command) {
        if (assignorRepository.findById(command.assignorId()).isEmpty()) {
            throw new AssignorNotFoundException(command.assignorId());
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        if (command.dueDate().isBefore(today)) {
            // Titulo vencido nao entra pelo fluxo normal: precificar prazo negativo
            // significaria "acrescimo", nao desagio.
            throw new InvalidPricingInputException(
                    "Vencimento %s anterior a data de hoje %s".formatted(command.dueDate(), today));
        }

        Receivable receivable = new Receivable(
                UUID.randomUUID(),
                command.assignorId(),
                command.type(),
                command.faceMoney(),
                command.dueDate(),
                ReceivableStatus.PENDING,
                0L);

        return receivableRepository.save(receivable);
    }

    @Transactional(readOnly = true)
    public Receivable findById(UUID id) {
        return receivableRepository.findById(id)
                .orElseThrow(() -> new ReceivableNotFoundException(id));
    }
}
