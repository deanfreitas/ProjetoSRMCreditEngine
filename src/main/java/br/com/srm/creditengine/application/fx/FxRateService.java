package br.com.srm.creditengine.application.fx;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateAlreadyRegisteredException;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Currency Engine: registra e prove cotacoes.
 *
 * <p>Somente insercao. Cotacao gravada nao e alterada nem apagada, porque ela e parte da
 * prova de como uma liquidacao foi calculada.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    private final FxRateRepository fxRateRepository;
    private final FxRateProvider fxRateProvider;
    private final Clock clock;

    public FxRateService(FxRateRepository fxRateRepository, FxRateProvider fxRateProvider, Clock clock) {
        this.fxRateRepository = fxRateRepository;
        this.fxRateProvider = fxRateProvider;
        this.clock = clock;
    }

    @Transactional
    public FxRate register(RegisterFxRateCommand command) {
        Instant effectiveAt = command.effectiveAt() != null ? command.effectiveAt() : clock.instant();
        String source = command.source() != null && !command.source().isBlank()
                ? command.source()
                : "MANUAL";

        FxRate rate = new FxRate(
                command.baseCurrency(),
                command.quoteCurrency(),
                command.rate(),
                effectiveAt,
                source);

        try {
            FxRate saved = fxRateRepository.save(rate);
            log.info("Cotacao registrada: pair={}/{} rate={} effectiveAt={} source={}",
                    saved.baseCurrency(), saved.quoteCurrency(), saved.rate(), saved.effectiveAt(), saved.source());
            return saved;
        } catch (DuplicateKeyException e) {
            throw new FxRateAlreadyRegisteredException(
                    command.baseCurrency(), command.quoteCurrency(), effectiveAt);
        }
    }

    /**
     * Cotacao que uma liquidacao feita agora usaria.
     *
     * <p>Passa pelo provider (nao pelo repositorio) de proposito: a resposta desta rota
     * precisa refletir a mesma regra de vigencia e defasagem aplicada na liquidacao.
     */
    @Transactional(readOnly = true)
    public FxRate current(Currency baseCurrency, Currency quoteCurrency) {
        return fxRateProvider.rateFor(baseCurrency, quoteCurrency, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<FxRate> history(Currency baseCurrency, Currency quoteCurrency, int limit) {
        return fxRateRepository.findHistory(baseCurrency, quoteCurrency, limit);
    }
}
