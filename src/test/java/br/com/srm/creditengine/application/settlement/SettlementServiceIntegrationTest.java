package br.com.srm.creditengine.application.settlement;

import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableNotFoundException;
import br.com.srm.creditengine.domain.receivable.ReceivableNotSettleableException;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.Settlement;
import br.com.srm.creditengine.support.AbstractIntegrationTest;
import br.com.srm.creditengine.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Liquidacao contra PostgreSQL real: ACID, idempotencia, concorrencia e auditoria.
 *
 * <p>O prazo e derivado da data de vencimento, entao os vencimentos abaixo sao construidos
 * em multiplos de 30 dias a partir da data de referencia para reproduzir exatamente os
 * golden cases - o que tambem prova que o caminho completo (banco + prazo + cambio)
 * continua batendo ao centavo, nao so o motor isolado.
 */
class SettlementServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private AssignorRepository assignorRepository;

    @Autowired
    private ReceivableRepository receivableRepository;

    @Autowired
    private FxRateRepository fxRateRepository;

    @Autowired
    private Clock clock;

    private Assignor assignor;

    @BeforeEach
    void setUp() {
        truncateAll();
        assignor = assignorRepository.save(TestFixtures.assignor("12345678000199"));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private Receivable givenReceivable(ReceivableType type, String faceValue, int daysToDue) {
        return receivableRepository.save(TestFixtures.receivable(
                assignor.id(), type, faceValue, Currency.BRL, today().plusDays(daysToDue)));
    }

    @Test
    @DisplayName("C1 end-to-end: duplicata de 3 meses liquidada em BRL grava R$ 92.859,94 e marca o recebivel")
    void settlesDomesticDuplicata() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);

        SettlementOutcome outcome = settlementService.settle(new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-c1"));

        assertThat(outcome.replayed()).isFalse();
        Settlement settlement = outcome.settlement();
        assertThat(settlement.presentValue()).isEqualTo(Money.of("92859.94", Currency.BRL));
        assertThat(settlement.settlementAmount()).isEqualTo(Money.of("92859.94", Currency.BRL));
        assertThat(settlement.discount()).isEqualTo(Money.of("7140.06", Currency.BRL));
        assertThat(settlement.termMonths()).isEqualTo(3);
        assertThat(settlement.optionalFxRate()).isEmpty();

        assertThat(receivableRepository.findById(receivable.id()))
                .get()
                .extracting(Receivable::status, Receivable::version)
                .containsExactly(ReceivableStatus.SETTLED, 1L);
    }

    @Test
    @DisplayName("C3 end-to-end: pagamento em USD usa a cotacao vigente e a grava na auditoria")
    void settlesCrossCurrencyWithStoredRate() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);
        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "5.4321",
                clock.instant().minusSeconds(60), "MANUAL"));

        Settlement settlement = settlementService.settle(new SettleReceivableCommand(
                receivable.id(), Currency.USD, "key-c3")).settlement();

        assertThat(settlement.settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
        assertThat(settlement.presentValue()).isEqualTo(Money.of("92859.94", Currency.BRL));
        FxRate applied = settlement.optionalFxRate().orElseThrow();
        // comparacao por valor: o banco devolve NUMERIC(19,6), ou seja escala 6
        assertThat(applied.rate()).isEqualByComparingTo("5.4321");
        assertThat(applied.baseCurrency()).isEqualTo(Currency.USD);
        assertThat(applied.quoteCurrency()).isEqualTo(Currency.BRL);
        assertThat(applied.source()).isEqualTo("MANUAL");

        // a taxa aplicada foi copiada para o registro, com par e fonte
        assertThat(jdbcClient.sql("""
                        SELECT fx_rate, fx_base_currency, fx_quote_currency, fx_rate_source
                          FROM settlements WHERE id = :id
                        """)
                .param("id", settlement.id())
                .query((rs, rowNum) -> rs.getBigDecimal("fx_rate").stripTrailingZeros())
                .single()).isEqualByComparingTo("5.4321");
    }

    @Test
    @DisplayName("Idempotencia: mesma chave repetida devolve a liquidacao original e nao paga de novo")
    void replaysSameIdempotencyKey() {
        Receivable receivable = givenReceivable(ReceivableType.CHEQUE_PRE_DATADO, "25000.00", 60);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-retry");

        SettlementOutcome first = settlementService.settle(command);
        SettlementOutcome second = settlementService.settle(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.settlement().id()).isEqualTo(first.settlement().id());
        assertThat(first.settlement().settlementAmount()).isEqualTo(Money.of("23337.77", Currency.BRL));

        assertThat(countSettlements(receivable.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Mesma chave com payload diferente e conflito, nao idempotencia")
    void rejectsReusedKeyWithDifferentPayload() {
        Receivable first = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "10000.00", 30);
        Receivable second = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "20000.00", 30);

        settlementService.settle(new SettleReceivableCommand(first.id(), Currency.BRL, "key-shared"));

        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(second.id(), Currency.BRL, "key-shared")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(countSettlements(second.id())).isZero();
        assertThat(receivableRepository.findById(second.id())).get()
                .extracting(Receivable::status).isEqualTo(ReceivableStatus.PENDING);
    }

    @Test
    @DisplayName("Recebivel ja liquidado recusa nova liquidacao com chave nova")
    void rejectsAlreadySettledReceivable() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "10000.00", 30);
        settlementService.settle(new SettleReceivableCommand(receivable.id(), Currency.BRL, "key-1"));

        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(receivable.id(), Currency.BRL, "key-2")))
                .isInstanceOf(ReceivableNotSettleableException.class);

        assertThat(countSettlements(receivable.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Recebivel inexistente resulta em erro de nao encontrado, nao em liquidacao vazia")
    void rejectsUnknownReceivable() {
        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(UUID.randomUUID(), Currency.BRL, "key-unknown")))
                .isInstanceOf(ReceivableNotFoundException.class);
    }

    @Test
    @DisplayName("Atomicidade: cambio indisponivel aborta tudo - recebivel continua PENDING e nada e gravado")
    void rollsBackWhenFxIsUnavailable() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);
        // nenhuma cotacao USD/BRL gravada

        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(receivable.id(), Currency.USD, "key-no-fx")))
                .isInstanceOf(FxRateUnavailableException.class);

        assertThat(countSettlements(receivable.id())).isZero();
        assertThat(receivableRepository.findById(receivable.id())).get()
                .extracting(Receivable::status, Receivable::version)
                .containsExactly(ReceivableStatus.PENDING, 0L);
    }

    @Test
    @DisplayName("Cotacao defasada nao liquida: falha explicita em vez de taxa velha")
    void rejectsStaleFxRate() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);
        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "5.4321",
                clock.instant().minus(java.time.Duration.ofDays(3)), "MANUAL"));

        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(receivable.id(), Currency.USD, "key-stale")))
                .isInstanceOf(FxRateUnavailableException.class);

        assertThat(countSettlements(receivable.id())).isZero();
    }

    @Test
    @DisplayName("Concorrencia: duas liquidacoes simultaneas do mesmo recebivel geram exatamente um pagamento")
    void concurrentSettlementsProduceExactlyOnePayment() throws Exception {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);

        int attempts = 8;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String key = "key-concurrent-" + i;
                tasks.add(() -> {
                    try {
                        settlementService.settle(new SettleReceivableCommand(
                                receivable.id(), Currency.BRL, key));
                        succeeded.incrementAndGet();
                    } catch (RuntimeException e) {
                        // conflito tratado: optimistic locking, estado ja liquidado ou
                        // unicidade no banco - todos terminam em 409 na camada HTTP
                        failed.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(succeeded.get()).as("apenas uma liquidacao pode vencer").isEqualTo(1);
        assertThat(failed.get()).isEqualTo(attempts - 1);
        assertThat(countSettlements(receivable.id())).isEqualTo(1);
        assertThat(receivableRepository.findById(receivable.id())).get()
                .extracting(Receivable::status, Receivable::version)
                .containsExactly(ReceivableStatus.SETTLED, 1L);
    }

    @Test
    @DisplayName("Auditoria: o banco rejeita UPDATE e DELETE em liquidacao registrada")
    void settlementRecordIsImmutableInTheDatabase() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);
        Settlement settlement = settlementService.settle(new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-immutable")).settlement();

        assertThatThrownBy(() -> jdbcClient
                .sql("UPDATE settlements SET settlement_amount = 1 WHERE id = :id")
                .param("id", settlement.id())
                .update())
                .hasMessageContaining("imutavel");

        assertThatThrownBy(() -> jdbcClient
                .sql("DELETE FROM settlements WHERE id = :id")
                .param("id", settlement.id())
                .update())
                .hasMessageContaining("imutavel");
    }

    @Test
    @DisplayName("Cotacao agendada para o futuro nao e usada na liquidacao de hoje")
    void ignoresFutureDatedRate() {
        Receivable receivable = givenReceivable(ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90);
        Instant now = clock.instant();
        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "5.4321", now.minusSeconds(120), "MANUAL"));
        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "9.9999", now.plusSeconds(3600), "MANUAL"));

        Settlement settlement = settlementService.settle(new SettleReceivableCommand(
                receivable.id(), Currency.USD, "key-future")).settlement();

        assertThat(settlement.optionalFxRate().orElseThrow().rate()).isEqualByComparingTo("5.4321");
        assertThat(settlement.settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
    }

    private int countSettlements(UUID receivableId) {
        return jdbcClient.sql("SELECT count(*) FROM settlements WHERE receivable_id = :id")
                .param("id", receivableId)
                .query(Integer.class)
                .single();
    }
}
