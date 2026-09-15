package br.com.srm.creditengine.application.statement;

import br.com.srm.creditengine.application.settlement.SettleReceivableCommand;
import br.com.srm.creditengine.application.settlement.SettlementService;
import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.assignor.AssignorRepository;
import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateRepository;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.InvalidPricingInputException;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.support.AbstractIntegrationTest;
import br.com.srm.creditengine.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extrato analitico contra PostgreSQL real.
 *
 * <p>Os filtros, a contagem e os totais precisam ser verificados no banco de verdade: e o
 * SQL (nao o Java) que decide se o periodo e inclusivo, se o join duplica linha e se a
 * paginacao e estavel. A massa e criada pelo proprio fluxo de liquidacao, para o extrato
 * ler o que o sistema realmente grava.
 */
class SettlementStatementQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SettlementStatementQuery statementQuery;

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

    private Assignor industria;
    private Assignor comercio;

    @BeforeEach
    void setUp() {
        truncateAll();
        industria = assignorRepository.save(TestFixtures.assignor("12345678000199"));
        comercio = assignorRepository.save(TestFixtures.assignor("98765432000111"));
        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "5.4321",
                clock.instant().minusSeconds(60), "MESA_OPERACOES"));

        // 4 liquidacoes: 3 pagas em BRL (duas do mesmo cedente) e 1 paga em USD
        settle(industria, ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90, Currency.BRL, "key-a1");
        settle(industria, ReceivableType.CHEQUE_PRE_DATADO, "25000.00", 60, Currency.BRL, "key-a2");
        settle(industria, ReceivableType.DUPLICATA_MERCANTIL, "100000.00", 90, Currency.USD, "key-a3");
        settle(comercio, ReceivableType.DUPLICATA_MERCANTIL, "10000.00", 30, Currency.BRL, "key-b1");
    }

    private void settle(Assignor assignor,
                        ReceivableType type,
                        String faceValue,
                        int daysToDue,
                        Currency settlementCurrency,
                        String idempotencyKey) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        Receivable receivable = receivableRepository.save(TestFixtures.receivable(
                assignor.id(), type, faceValue, Currency.BRL, today.plusDays(daysToDue)));
        settlementService.settle(new SettleReceivableCommand(
                receivable.id(), settlementCurrency, idempotencyKey));
    }

    private StatementFilter filter(int page, int size) {
        return new StatementFilter(null, null, null, null, page, size);
    }

    @Test
    @DisplayName("Sem filtro: devolve todas as liquidacoes com dados do cedente e do recebivel")
    void returnsEveryStatementEntry() {
        StatementPage result = statementQuery.findBy(filter(0, 20));

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.content()).hasSize(4);
        assertThat(result.hasNext()).isFalse();

        // o join traz o cedente e o tipo do ativo, que nao existem na tabela de liquidacao
        assertThat(result.content())
                .extracting(StatementEntry::assignorDocument)
                .containsOnly("12345678000199", "98765432000111");
        assertThat(result.content())
                .allSatisfy(entry -> {
                    assertThat(entry.assignorLegalName()).isNotBlank();
                    assertThat(entry.receivableType()).isNotNull();
                    assertThat(entry.dueDate()).isAfter(LocalDate.ofInstant(clock.instant(), clock.getZone()));
                });
    }

    @Test
    @DisplayName("Totais por par de moedas: soma dentro da moeda, nunca entre moedas")
    void aggregatesTotalsPerCurrencyPair() {
        StatementPage result = statementQuery.findBy(filter(0, 20));

        assertThat(result.totals()).hasSize(2);

        StatementTotal domestic = result.totals().stream()
                .filter(total -> total.settlementAmount().currency() == Currency.BRL)
                .findFirst().orElseThrow();
        assertThat(domestic.settlements()).isEqualTo(3);
        assertThat(domestic.faceValue()).isEqualTo(Money.of("135000.00", Currency.BRL));
        // 92.859,94 + 23.337,77 + 9.756,10
        assertThat(domestic.settlementAmount()).isEqualTo(Money.of("125953.81", Currency.BRL));

        StatementTotal crossCurrency = result.totals().stream()
                .filter(total -> total.settlementAmount().currency() == Currency.USD)
                .findFirst().orElseThrow();
        assertThat(crossCurrency.settlements()).isEqualTo(1);
        assertThat(crossCurrency.settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
        // o desagio fica na moeda de face, nao na moeda paga
        assertThat(crossCurrency.discount()).isEqualTo(Money.of("7140.06", Currency.BRL));
    }

    @Test
    @DisplayName("Filtro por cedente isola a contraparte")
    void filtersByAssignor() {
        StatementPage result = statementQuery.findBy(
                new StatementFilter(null, null, comercio.id(), null, 0, 20));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).singleElement()
                .extracting(StatementEntry::assignorId).isEqualTo(comercio.id());
        assertThat(result.totals()).singleElement()
                .extracting(StatementTotal::settlementAmount)
                .isEqualTo(Money.of("9756.10", Currency.BRL));
    }

    @Test
    @DisplayName("Filtro por moeda de pagamento traz apenas a liquidacao cross-currency")
    void filtersBySettlementCurrency() {
        StatementPage result = statementQuery.findBy(
                new StatementFilter(null, null, null, Currency.USD, 0, 20));

        assertThat(result.totalElements()).isEqualTo(1);
        StatementEntry entry = result.content().getFirst();
        assertThat(entry.settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
        assertThat(entry.faceValue()).isEqualTo(Money.of("100000.00", Currency.BRL));
        assertThat(entry.fxRate()).isEqualByComparingTo("5.4321");
    }

    @Test
    @DisplayName("Paginacao acontece no banco: total e do filtro, nao da pagina")
    void paginatesServerSide() {
        StatementPage first = statementQuery.findBy(filter(0, 2));
        StatementPage second = statementQuery.findBy(filter(1, 2));

        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();

        assertThat(second.content()).hasSize(2);
        assertThat(second.hasNext()).isFalse();

        // paginas disjuntas: a ordenacao desempata por id
        assertThat(first.content()).extracting(StatementEntry::settlementId)
                .doesNotContainAnyElementsOf(second.content().stream().map(StatementEntry::settlementId).toList());

        // o total nao e recalculado por pagina
        assertThat(second.totals()).isEqualTo(first.totals());
    }

    @Test
    @DisplayName("Periodo fora da janela devolve pagina vazia, sem totais")
    void filtersByPeriod() {
        StatementPage future = statementQuery.findBy(new StatementFilter(
                clock.instant().plus(Duration.ofDays(1)), null, null, null, 0, 20));

        assertThat(future.totalElements()).isZero();
        assertThat(future.content()).isEmpty();
        assertThat(future.totals()).isEmpty();

        StatementPage today = statementQuery.findBy(new StatementFilter(
                clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(1)),
                null, null, 0, 20));
        assertThat(today.totalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("Cedente sem liquidacao devolve pagina vazia, nao erro")
    void returnsEmptyPageForUnknownAssignor() {
        StatementPage result = statementQuery.findBy(
                new StatementFilter(null, null, UUID.randomUUID(), null, 0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    @DisplayName("Tamanho de pagina fora do limite e periodo invertido sao recusados")
    void rejectsAbusivePaging() {
        assertThatThrownBy(() -> filter(0, 0)).isInstanceOf(InvalidPricingInputException.class);
        assertThatThrownBy(() -> filter(0, StatementFilter.MAX_PAGE_SIZE + 1))
                .isInstanceOf(InvalidPricingInputException.class);
        assertThatThrownBy(() -> filter(-1, 20)).isInstanceOf(InvalidPricingInputException.class);
        assertThatThrownBy(() -> new StatementFilter(
                clock.instant(), clock.instant().minusSeconds(1), null, null, 0, 20))
                .isInstanceOf(InvalidPricingInputException.class);
    }
}
