package br.com.srm.creditengine.application.settlement;

import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.IdempotencyReservation;
import br.com.srm.creditengine.domain.settlement.IdempotencyStore;
import br.com.srm.creditengine.domain.settlement.Settlement;
import br.com.srm.creditengine.domain.settlement.SettlementInProgressException;
import br.com.srm.creditengine.domain.settlement.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso de liquidacao, com a idempotencia decidida em Redis <b>antes</b> do trabalho
 * caro.
 *
 * <p>O que este metodo faz, na ordem:
 * <ol>
 *   <li><b>reserva a chave no guarda</b> ({@code SET NX PX}, sub-milissegundo): duplo
 *       clique e retry de rede sao respondidos aqui, sem resolver cotacao, sem precificar e
 *       sem tomar conexao do pool;</li>
 *   <li><b>delega a transacao</b> a {@link SettlementTransaction}, onde o UPDATE do
 *       recebivel e o INSERT da liquidacao acontecem juntos ou nao acontecem;</li>
 *   <li><b>publica a conclusao</b> no guarda somente depois do commit - o que fica em Redis
 *       e resultado consumado, nunca intencao;</li>
 *   <li><b>compensa a reserva</b> se nada foi gravado. Sem isso, um {@code 503} de cotacao
 *       deixaria a chave reservada sem pagamento nenhum e o retry legitimo do cliente
 *       receberia "duplicado" para dinheiro que nunca saiu - falha silenciosa, pior que
 *       indisponibilidade.</li>
 * </ol>
 *
 * <p><b>O Redis nao e a autoridade.</b> Ele decide <i>antes</i>; quem decide <i>de fato</i>
 * continua sendo o PostgreSQL, com {@code uk_settlements_idempotency_key} e
 * {@code uk_settlements_receivable} commitados na mesma transacao do dinheiro. Por isso todo
 * caminho de excecao deste codigo termina no banco:
 * <ul>
 *   <li>guarda indisponivel ({@code UNAVAILABLE}) -&gt; a transacao volta a consultar
 *       {@code settlements} e o comportamento e exatamente o anterior ao Redis, so mais
 *       caro. Idempotencia nunca e causa de indisponibilidade;</li>
 *   <li>guarda diz {@code COMPLETED} mas o banco nao tem a liquidacao (TTL torto, chave
 *       perdida, ordem quebrada) -&gt; o banco manda, e o caminho completo e refeito;</li>
 *   <li>corrida perdida no {@code INSERT} -&gt; releitura pela chave: se a liquidacao
 *       gravada e do mesmo pedido, a resposta correta e {@code 200} com ela, nao um
 *       {@code 409} por acidente de agendamento.</li>
 * </ul>
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementTransaction settlementTransaction;
    private final SettlementRepository settlementRepository;
    private final IdempotencyStore idempotencyStore;
    private final MeterRegistry meterRegistry;

    public SettlementService(SettlementTransaction settlementTransaction,
                             SettlementRepository settlementRepository,
                             IdempotencyStore idempotencyStore,
                             MeterRegistry meterRegistry) {
        this.settlementTransaction = settlementTransaction;
        this.settlementRepository = settlementRepository;
        this.idempotencyStore = idempotencyStore;
        this.meterRegistry = meterRegistry;
    }

    public SettlementOutcome settle(SettleReceivableCommand command) {
        String fingerprint = command.fingerprint();
        IdempotencyReservation reservation = idempotencyStore.reserve(command.idempotencyKey(), fingerprint);

        return switch (reservation.status()) {
            case CONFLICT -> {
                count("idempotency_conflict");
                throw new IdempotencyConflictException(command.idempotencyKey());
            }
            // Recusa cedo em vez de deixar as duas requisicoes precificarem para uma
            // perder no INSERT: o provedor de cotacao nao paga pelo duplo clique.
            case IN_PROGRESS -> {
                log.info("Requisicao concorrente com a mesma chave ainda em processamento: idempotencyKey={}",
                        command.idempotencyKey());
                count("in_progress");
                throw new SettlementInProgressException(command.idempotencyKey());
            }
            case COMPLETED -> replay(command, fingerprint, reservation);
            case ACQUIRED, UNAVAILABLE -> executeGuardedBy(reservation, command, fingerprint);
            default -> throw new IllegalStateException(
                    "Estado de reserva de idempotencia nao tratado: " + reservation.status());
        };
    }

    /**
     * Retry de chave concluida: o guarda diz quem e, o banco diz o que foi gravado.
     *
     * <p>A leitura no banco nao e desperdicio de uma "otimizacao perdida" - e a recusa
     * deliberada de responder valor de dinheiro a partir de cache. O que se economiza aqui
     * e o caminho caro (cotacao, precificacao, transacao de escrita), nao a fonte da
     * verdade.
     */
    private SettlementOutcome replay(SettleReceivableCommand command,
                                     String fingerprint,
                                     IdempotencyReservation reservation) {
        Optional<Settlement> stored = settlementRepository.findByIdempotencyKey(command.idempotencyKey());

        if (stored.isEmpty()) {
            log.warn("Guarda de idempotencia aponta conclusao inexistente no banco; refazendo o caminho completo: "
                    + "idempotencyKey={} settlementId={}", command.idempotencyKey(),
                    reservation.optionalSettlementId().orElse(null));
            count("phantom_completion");
            return executeGuardedBy(
                    IdempotencyReservation.unavailable(command.idempotencyKey(), fingerprint),
                    command,
                    fingerprint);
        }

        Settlement settlement = stored.get();
        if (!settlement.requestFingerprint().equals(fingerprint)) {
            // Cinto e suspensorio: o guarda ja recusaria pelo fingerprint, mas quem tem a
            // ultima palavra sobre o que foi pago e a linha gravada.
            count("idempotency_conflict");
            throw new IdempotencyConflictException(command.idempotencyKey());
        }

        log.info("Liquidacao repetida devolvida sem novo pagamento: settlementId={} receivableId={} idempotencyKey={}",
                settlement.id(), settlement.receivableId(), command.idempotencyKey());
        count("replayed");
        return SettlementOutcome.replayed(settlement);
    }

    private SettlementOutcome executeGuardedBy(IdempotencyReservation reservation,
                                               SettleReceivableCommand command,
                                               String fingerprint) {
        boolean settled = false;
        try {
            SettlementOutcome outcome = settlementTransaction.execute(command, fingerprint, reservation.guarded());
            settled = true;
            idempotencyStore.complete(reservation, outcome.settlement().id());
            return outcome;
        } catch (ConcurrentSettlementException e) {
            Optional<Settlement> winner = settlementRepository.findByIdempotencyKey(command.idempotencyKey());
            if (winner.isPresent() && winner.get().requestFingerprint().equals(fingerprint)) {
                Settlement settlement = winner.get();
                settled = true;
                idempotencyStore.complete(reservation, settlement.id());
                log.info("Corrida perdida na mesma chave: devolvendo a liquidacao vencedora: settlementId={} "
                        + "idempotencyKey={}", settlement.id(), command.idempotencyKey());
                count("replayed");
                return SettlementOutcome.replayed(settlement);
            }
            throw e;
        } finally {
            if (!settled) {
                // Compensacao no rollback. Esta e a linha que separa "Redis ajudando" de
                // "Redis mentindo": falha de cambio nao pode deixar chave orfa bloqueando
                // o retry de um pagamento que nunca aconteceu.
                idempotencyStore.release(reservation);
            }
        }
    }

    private void count(String result) {
        meterRegistry.counter("credit_engine.settlements", "result", result).increment();
    }

    /** Exposto para o recurso de consulta: liquidacao e somente leitura apos gravada. */
    @Transactional(readOnly = true)
    public Optional<Settlement> findByReceivable(UUID receivableId) {
        return settlementRepository.findByReceivableId(receivableId);
    }
}
