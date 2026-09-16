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
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.IdempotencyReservation;
import br.com.srm.creditengine.domain.settlement.IdempotencyStore;
import br.com.srm.creditengine.domain.settlement.SettlementInProgressException;
import br.com.srm.creditengine.domain.settlement.SettlementRepository;
import br.com.srm.creditengine.infrastructure.idempotency.RedisIdempotencyStore;
import br.com.srm.creditengine.support.AbstractIntegrationTest;
import br.com.srm.creditengine.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guarda de idempotencia em Redis, contra Redis e PostgreSQL reais.
 *
 * <p>Estes testes existem porque trocar a idempotencia para Redis introduz um segundo
 * datastore no caminho do dinheiro, e com ele quatro modos de falha novos que nao existiam
 * quando a decisao era um {@code SELECT} na mesma transacao:
 *
 * <ol>
 *   <li>reserva orfa depois de rollback - o pior deles: recusaria o retry de um pagamento
 *       que nunca aconteceu;</li>
 *   <li>Redis indisponivel - nao pode virar indisponibilidade da liquidacao;</li>
 *   <li>guarda afirmando conclusao que o banco nao tem;</li>
 *   <li>duas requisicoes simultaneas na mesma chave.</li>
 * </ol>
 *
 * <p>Cada um tem um teste abaixo. Sem eles, "trocamos para Redis" seria afirmacao.
 */
class SettlementIdempotencyRedisIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "srm:idempotency:settlement:";

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementTransaction settlementTransaction;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @Autowired
    private AssignorRepository assignorRepository;

    @Autowired
    private ReceivableRepository receivableRepository;

    @Autowired
    private FxRateRepository fxRateRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    private Assignor assignor;

    @BeforeEach
    void setUp() {
        truncateAll();
        assignor = assignorRepository.save(TestFixtures.assignor("12345678000199"));
    }

    private Receivable givenReceivable(String faceValue, int daysToDue) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        return receivableRepository.save(TestFixtures.receivable(
                assignor.id(), ReceivableType.DUPLICATA_MERCANTIL, faceValue, Currency.BRL, today.plusDays(daysToDue)));
    }

    @Test
    @DisplayName("Chave concluida fica marcada em Redis apos o commit, e o retry devolve a liquidacao original")
    void publishesCompletionAfterCommitAndReplaysFromIt() {
        Receivable receivable = givenReceivable("100000.00", 90);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-completed");

        SettlementOutcome first = settlementService.settle(command);

        String stored = redisTemplate.opsForValue().get(KEY_PREFIX + "key-completed");
        assertThat(stored)
                .as("o que vai para o Redis e resultado consumado, com o id da liquidacao")
                .startsWith("COMPLETED|")
                .endsWith(first.settlement().id().toString());
        assertThat(redisTemplate.getExpire(KEY_PREFIX + "key-completed"))
                .as("conclusao expira: idempotencia de longo prazo e do banco, nao do cache")
                .isPositive();

        SettlementOutcome replay = settlementService.settle(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.settlement().id()).isEqualTo(first.settlement().id());
        assertThat(countSettlements(receivable.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Chave reservada e ainda em processamento recusa a requisicao gemea antes do trabalho caro")
    void rejectsTwinRequestWhileFirstIsStillRunning() {
        Receivable receivable = givenReceivable("100000.00", 90);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-in-flight");

        // Simula a primeira requisicao ainda dentro da transacao: a reserva existe, o
        // commit nao aconteceu.
        IdempotencyReservation inFlight = idempotencyStore.reserve("key-in-flight", command.fingerprint());
        assertThat(inFlight.status()).isEqualTo(IdempotencyReservation.Status.ACQUIRED);

        assertThatThrownBy(() -> settlementService.settle(command))
                .isInstanceOf(SettlementInProgressException.class);

        assertThat(countSettlements(receivable.id()))
                .as("a gemea foi recusada na borda: nada de cotacao, precificacao ou INSERT")
                .isZero();
        assertThat(receivableRepository.findById(receivable.id())).get()
                .extracting(Receivable::status).isEqualTo(ReceivableStatus.PENDING);
    }

    @Test
    @DisplayName("Rollback compensa a reserva: retry legitimo apos falha de cambio liquida normalmente")
    void releasesReservationWhenTransactionRollsBack() {
        Receivable receivable = givenReceivable("100000.00", 90);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.USD, "key-compensated");

        // Sem cotacao USD/BRL: a transacao abre, falha e desfaz.
        assertThatThrownBy(() -> settlementService.settle(command))
                .isInstanceOf(FxRateUnavailableException.class);

        assertThat(redisTemplate.hasKey(KEY_PREFIX + "key-compensated"))
                .as("chave orfa aqui recusaria o retry de um pagamento que nunca aconteceu")
                .isFalse();

        fxRateRepository.save(FxRate.of(Currency.USD, Currency.BRL, "5.4321",
                clock.instant().minusSeconds(60), "MANUAL"));

        SettlementOutcome retry = settlementService.settle(command);

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.settlement().settlementAmount()).isEqualTo(Money.of("17094.67", Currency.USD));
        assertThat(countSettlements(receivable.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Mesma chave com payload diferente e recusada na borda, sem abrir transacao")
    void rejectsReusedKeyAtTheEdge() {
        Receivable first = givenReceivable("10000.00", 30);
        Receivable second = givenReceivable("20000.00", 30);

        settlementService.settle(new SettleReceivableCommand(first.id(), Currency.BRL, "key-shared"));

        assertThatThrownBy(() -> settlementService.settle(
                new SettleReceivableCommand(second.id(), Currency.BRL, "key-shared")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(countSettlements(second.id())).isZero();
        assertThat(receivableRepository.findById(second.id())).get()
                .extracting(Receivable::status).isEqualTo(ReceivableStatus.PENDING);
    }

    @Test
    @DisplayName("Guarda afirmando conclusao que o banco nao tem: o banco manda e a liquidacao acontece")
    void databaseWinsOverPhantomCompletion() {
        Receivable receivable = givenReceivable("100000.00", 90);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-phantom");

        // Estado impossivel de produzir pelo caminho normal, possivel em incidente:
        // conclusao publicada para uma liquidacao que nao existe.
        redisTemplate.opsForValue().set(
                KEY_PREFIX + "key-phantom",
                "COMPLETED|" + command.fingerprint() + "|" + UUID.randomUUID(),
                Duration.ofMinutes(5));

        SettlementOutcome outcome = settlementService.settle(command);

        assertThat(outcome.replayed())
                .as("nao se responde pagamento a partir de cache: o registro precisa existir")
                .isFalse();
        assertThat(countSettlements(receivable.id())).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "key-phantom"))
                .endsWith(outcome.settlement().id().toString());
    }

    @Test
    @DisplayName("Redis fora do ar nao derruba a liquidacao: o caminho do PostgreSQL responde, inclusive o retry")
    void failsOpenWhenRedisIsDown() throws IOException {
        Receivable receivable = givenReceivable("100000.00", 90);
        SettleReceivableCommand command = new SettleReceivableCommand(
                receivable.id(), Currency.BRL, "key-fail-open");

        IdempotencyStore unreachable = storeOnClosedPort();

        assertThat(unreachable.reserve("key-fail-open", command.fingerprint()).status())
                .as("contrato da porta: falha de infraestrutura nao lanca, degrada")
                .isEqualTo(IdempotencyReservation.Status.UNAVAILABLE);

        SettlementService degraded = new SettlementService(
                settlementTransaction, settlementRepository, unreachable, meterRegistry);

        SettlementOutcome first = degraded.settle(command);
        SettlementOutcome retry = degraded.settle(command);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed())
                .as("sem guarda, a idempotencia volta para dentro da transacao e continua valendo")
                .isTrue();
        assertThat(retry.settlement().id()).isEqualTo(first.settlement().id());
        assertThat(countSettlements(receivable.id())).isEqualTo(1);
    }

    /**
     * Guarda apontado para uma porta sem ninguem escutando.
     *
     * <p>Porta reservada e liberada em seguida: mais confiavel que chutar um numero que
     * pode estar em uso na maquina de quem roda a suite.
     */
    private IdempotencyStore storeOnClosedPort() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", closedPort);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        return new RedisIdempotencyStore(template, meterRegistry, KEY_PREFIX,
                Duration.ofSeconds(30), Duration.ofHours(24));
    }

    private int countSettlements(UUID receivableId) {
        return jdbcClient.sql("SELECT count(*) FROM settlements WHERE receivable_id = :id")
                .param("id", receivableId)
                .query(Integer.class)
                .single();
    }
}
