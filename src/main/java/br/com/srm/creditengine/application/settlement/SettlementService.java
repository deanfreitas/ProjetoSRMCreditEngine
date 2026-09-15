package br.com.srm.creditengine.application.settlement;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.pricing.PricingEngine;
import br.com.srm.creditengine.domain.pricing.PricingInput;
import br.com.srm.creditengine.domain.pricing.PricingResult;
import br.com.srm.creditengine.domain.pricing.TermCalculator;
import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableNotFoundException;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.Settlement;
import br.com.srm.creditengine.domain.settlement.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso de liquidacao: o ponto onde ACID, idempotencia, concorrencia e auditoria se
 * encontram.
 *
 * <p>Ordem deliberada das etapas:
 * <ol>
 *   <li><b>idempotencia primeiro</b>: se a chave ja produziu liquidacao, devolve a original
 *       sem recalcular nem repagar (retry de rede e duplo clique sao certeza);</li>
 *   <li>resolve recebivel e valida elegibilidade;</li>
 *   <li>resolve a cotacao <b>uma vez</b> e precifica com ela - a mesma taxa que sera
 *       gravada na auditoria;</li>
 *   <li>grava a transicao do recebivel com optimistic locking e insere a liquidacao
 *       <b>na mesma transacao</b>: ou as duas coisas acontecem, ou nenhuma.</li>
 * </ol>
 *
 * <p>Compare com o Anexo A: lá o INSERT e o UPDATE rodavam sem transacao e o {@code catch}
 * vazio deixava o sistema em estado inconsistente de proposito.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final ReceivableRepository receivableRepository;
    private final SettlementRepository settlementRepository;
    private final FxRateProvider fxRateProvider;
    private final PricingEngine pricingEngine;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public SettlementService(ReceivableRepository receivableRepository,
                             SettlementRepository settlementRepository,
                             FxRateProvider fxRateProvider,
                             PricingEngine pricingEngine,
                             Clock clock,
                             MeterRegistry meterRegistry) {
        this.receivableRepository = receivableRepository;
        this.settlementRepository = settlementRepository;
        this.fxRateProvider = fxRateProvider;
        this.pricingEngine = pricingEngine;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public SettlementOutcome settle(SettleReceivableCommand command) {
        String fingerprint = command.fingerprint();

        Optional<Settlement> previous = settlementRepository.findByIdempotencyKey(command.idempotencyKey());
        if (previous.isPresent()) {
            Settlement settlement = previous.get();
            if (!settlement.requestFingerprint().equals(fingerprint)) {
                count("idempotency_conflict");
                throw new IdempotencyConflictException(command.idempotencyKey());
            }
            log.info("Liquidacao repetida devolvida sem novo pagamento: settlementId={} receivableId={} idempotencyKey={}",
                    settlement.id(), settlement.receivableId(), command.idempotencyKey());
            count("replayed");
            return SettlementOutcome.replayed(settlement);
        }

        Receivable receivable = receivableRepository.findById(command.receivableId())
                .orElseThrow(() -> new ReceivableNotFoundException(command.receivableId()));

        Receivable settledReceivable = receivable.settled();

        Instant now = clock.instant();
        PricingResult pricing = price(receivable, command, now);

        receivableRepository.settle(settledReceivable);

        Settlement settlement = Settlement.from(
                UUID.randomUUID(),
                receivable.id(),
                receivable.assignorId(),
                command.idempotencyKey(),
                fingerprint,
                pricing,
                now);

        try {
            settlementRepository.save(settlement);
        } catch (DuplicateKeyException e) {
            // Corrida na mesma chave ou no mesmo recebivel: a unicidade do banco e a
            // ultima linha de defesa. Esta transacao perdeu e precisa ser desfeita -
            // o cliente repete a requisicao e recebe a liquidacao original.
            count("concurrent_conflict");
            throw new ConcurrentSettlementException(receivable.id(), e);
        }

        log.info("Liquidacao registrada: settlementId={} receivableId={} assignorId={} presentValue={} "
                        + "settlementAmount={} fxRate={} termMonths={}",
                settlement.id(), settlement.receivableId(), settlement.assignorId(),
                settlement.presentValue(), settlement.settlementAmount(),
                settlement.optionalFxRate().map(FxRate::rate).orElse(null),
                settlement.termMonths());
        count("created");

        return SettlementOutcome.created(settlement);
    }

    private PricingResult price(Receivable receivable, SettleReceivableCommand command, Instant now) {
        LocalDate referenceDate = LocalDate.ofInstant(now, clock.getZone());
        int termMonths = TermCalculator.termInMonths(referenceDate, receivable.dueDate());

        PricingInput input = buildInput(receivable, command, referenceDate, termMonths, now);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return pricingEngine.price(input);
        } finally {
            sample.stop(meterRegistry.timer("credit_engine.pricing.duration"));
        }
    }

    private PricingInput buildInput(Receivable receivable,
                                    SettleReceivableCommand command,
                                    LocalDate referenceDate,
                                    int termMonths,
                                    Instant now) {
        if (command.settlementCurrency() == receivable.faceValue().currency()) {
            return PricingInput.domestic(receivable.faceValue(), receivable.type(), termMonths, referenceDate);
        }

        // Resolvida uma unica vez e congelada no registro: o extrato nunca reinterpreta
        // qual taxa "seria" a vigente (SPEC.md, secao 2.5).
        FxRate fxRate = fxRateProvider.rateFor(
                command.settlementCurrency(), receivable.faceValue().currency(), now);

        return PricingInput.crossCurrency(
                receivable.faceValue(),
                receivable.type(),
                termMonths,
                command.settlementCurrency(),
                fxRate,
                referenceDate);
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
