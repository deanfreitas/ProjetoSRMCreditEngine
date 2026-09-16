package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.infrastructure.persistence.entity.FxRateJpaEntity;
import br.com.srm.creditengine.infrastructure.persistence.jpa.SpringDataFxRateRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaFxRateRepository implements FxRateRepository {

    private final SpringDataFxRateRepository springDataRepository;

    public JpaFxRateRepository(SpringDataFxRateRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public FxRate save(FxRate rate) {
        FxRateJpaEntity saved = springDataRepository.saveAndFlush(FxRateJpaEntity.fromDomain(rate));
        return saved.toDomain();
    }

    @Override
    public Optional<FxRate> findLatest(Currency baseCurrency, Currency quoteCurrency, Instant at) {
        return springDataRepository
                .findFirstByBaseCurrencyAndQuoteCurrencyAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                        baseCurrency, quoteCurrency, at)
                .map(FxRateJpaEntity::toDomain);
    }

    @Override
    public List<FxRate> findHistory(Currency baseCurrency, Currency quoteCurrency, int limit) {
        return springDataRepository
                .findByBaseCurrencyAndQuoteCurrencyOrderByEffectiveAtDesc(
                        baseCurrency, quoteCurrency, PageRequest.of(0, limit))
                .stream()
                .map(FxRateJpaEntity::toDomain)
                .toList();
    }
}
