package br.com.srm.creditengine.infrastructure.fx;

import br.com.srm.creditengine.application.settlement.SettleReceivableCommand;
import br.com.srm.creditengine.application.settlement.SettlementOutcome;
import br.com.srm.creditengine.application.settlement.SettlementService;
import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateProvider;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.support.AbstractIntegrationTest;
import br.com.srm.creditengine.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provedor externo ligado, contra PostgreSQL real.
 *
 * <p>Aqui o que se prova nao e a resiliencia (isso esta em
 * {@link ResilientFxRateProviderTest}, sem banco e sem Spring), e sim a <b>ligacao</b>:
 * com o provedor externo habilitado, a cotacao trazida por ele entra no historico, e a
 * liquidacao seguinte usa e <b>audita</b> essa taxa. Sem este teste, o mock poderia
 * funcionar isolado e nao estar plugado em nada.
 *
 * <p>Nas outras classes de integracao o provedor externo fica desligado de proposito: os
 * cenarios de "cotacao ausente = 503" precisam que ninguem saia buscando taxa por conta.
 */
@SpringBootTest(properties = {
        "credit-engine.fx.upstream.enabled=true",
        "credit-engine.fx.upstream.rates.USD=5.4321",
        "credit-engine.fx.upstream.latency=0ms",
        "credit-engine.fx.upstream.failure-rate=0"
})
class FxUpstreamRefreshIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FxRateProvider fxRateProvider;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private AssignorRepository assignorRepository;

    @Autowired
    private ReceivableRepository receivableRepository;

    @Autowired
    private Clock clock;

    private Assignor assignor;

    @BeforeEach
    void setUp() {
        truncateAll();
        assignor = assignorRepository.save(TestFixtures.assignor("98765432000188"));
    }

    private int storedRates() {
        return jdbcClient.sql("SELECT count(*) FROM fx_rates").query(Integer.class).single();
    }

    @Test
    @DisplayName("Sem cotacao em casa, a taxa vem do provedor externo e fica gravada no historico")
    void refreshesAndPersistsRate() {
        assertThat(storedRates()).isZero();

        FxRate rate = fxRateProvider.rateFor(Currency.USD, Currency.BRL, clock.instant());

        assertThat(rate.rate()).isEqualByComparingTo(new BigDecimal("5.4321"));
        assertThat(rate.source()).isEqualTo("MOCK_UPSTREAM");
        assertThat(storedRates()).isOne();

        // Segunda consulta e servida pelo historico: o caminho rapido existe para que a
        // liquidacao normal nao dependa de rede alheia.
        fxRateProvider.rateFor(Currency.USD, Currency.BRL, clock.instant());
        assertThat(storedRates()).isOne();
    }

    @Test
    @DisplayName("Liquidacao cross-currency usa a taxa do provedor externo e a congela na auditoria")
    void settlesUsingUpstreamRate() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        Receivable receivable = receivableRepository.save(TestFixtures.receivable(
                assignor.id(), ReceivableType.DUPLICATA_MERCANTIL, "100000.00", Currency.BRL, today.plusDays(90)));

        SettlementOutcome outcome = settlementService.settle(new SettleReceivableCommand(
                receivable.id(), Currency.USD, "key-upstream-fx"));

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.settlement().fxRate()).isNotNull();
        assertThat(outcome.settlement().fxRate().source()).isEqualTo("MOCK_UPSTREAM");
        // Golden case C3, agora com a taxa vinda do provedor externo em vez de cadastrada
        // na mao: duplicata de R$ 100.000,00 em 3 meses liquidada em USD a 5,4321.
        assertThat(outcome.settlement().settlementAmount().amount())
                .isEqualByComparingTo(new BigDecimal("17094.67"));

        String auditedSource = jdbcClient
                .sql("SELECT fx_rate_source FROM settlements WHERE receivable_id = :id")
                .param("id", receivable.id())
                .query(String.class)
                .single();
        assertThat(auditedSource).isEqualTo("MOCK_UPSTREAM");

        BigDecimal auditedRate = jdbcClient
                .sql("SELECT fx_rate FROM settlements WHERE receivable_id = :id")
                .param("id", receivable.id())
                .query(BigDecimal.class)
                .single();
        assertThat(auditedRate).isEqualByComparingTo(new BigDecimal("5.4321"));
    }
}
