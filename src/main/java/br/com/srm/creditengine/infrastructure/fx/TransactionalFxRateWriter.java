package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persiste a cotacao recem-obtida em <b>transacao propria</b>.
 *
 * <p>Motivo: a busca acontece dentro da transacao da liquidacao. Se ela gravasse junto e
 * a liquidacao fosse desfeita (optimistic locking perdido, por exemplo), a cotacao
 * tambem desapareceria - e a proxima tentativa iria bater no terceiro outra vez, no meio
 * de um pico. Em transacao separada, o historico fica gravado independente do desfecho da
 * liquidacao, que e o comportamento correto: a cotacao existiu, foi observada e vale como
 * fato.
 *
 * <p>Colisao de chave unica do par + vigencia nao e erro: significa que outra instancia
 * (ou outra requisicao concorrente) gravou a mesma cotacao primeiro. Nesse caso o INSERT
 * e descartado e a taxa continua valida - e append-only, ninguem sobrescreve nada.
 */
public class TransactionalFxRateWriter implements FxRateWriter {

    private static final Logger log = LoggerFactory.getLogger(TransactionalFxRateWriter.class);

    private final FxRateRepository fxRateRepository;
    private final TransactionTemplate transactionTemplate;

    public TransactionalFxRateWriter(FxRateRepository fxRateRepository,
                                     PlatformTransactionManager transactionManager) {
        this.fxRateRepository = fxRateRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public FxRate store(FxRate rate) {
        return transactionTemplate.execute(status -> {
            try {
                return fxRateRepository.save(rate);
            } catch (DataIntegrityViolationException e) {
                log.debug("Cotacao {}/{} com vigencia {} ja estava gravada; mantendo a existente",
                        rate.baseCurrency(), rate.quoteCurrency(), rate.effectiveAt());
                status.setRollbackOnly();
                return rate;
            }
        });
    }
}
