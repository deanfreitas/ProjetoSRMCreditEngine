package br.com.srm.creditengine.api;

import br.com.srm.creditengine.application.settlement.SettleReceivableCommand;
import br.com.srm.creditengine.application.settlement.SettlementOutcome;
import br.com.srm.creditengine.application.settlement.SettlementService;
import br.com.srm.creditengine.domain.fx.FxRate;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.receivable.ReceivableNotFoundException;
import br.com.srm.creditengine.domain.receivable.ReceivableNotSettleableException;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP da liquidacao, com o caso de uso simulado.
 *
 * <p>O que este teste protege e exatamente o que o Anexo A errava na fronteira: erro
 * saindo com {@code 200}, retry sem chave de idempotencia e conflito indistinguivel de
 * sucesso. A regra de negocio em si e verificada contra PostgreSQL real em
 * {@code SettlementServiceIntegrationTest} - aqui o alvo e status, header e corpo.
 */
@WebMvcTest(SettlementController.class)
class SettlementControllerTest {

    private static final UUID RECEIVABLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SettlementService settlementService;

    private static final String BODY = """
            {"receivableId":"11111111-1111-1111-1111-111111111111","settlementCurrency":"USD"}
            """;

    private static Settlement settlement() {
        return new Settlement(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                RECEIVABLE_ID,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "key-1",
                "fingerprint",
                3,
                new BigDecimal("0.010000"),
                new BigDecimal("0.015000"),
                Money.of("100000.00", Currency.BRL),
                Money.of("92859.94", Currency.BRL),
                Money.of("7140.06", Currency.BRL),
                Money.of("17094.67", Currency.USD),
                FxRate.of(Currency.USD, Currency.BRL, "5.4321", Instant.parse("2026-01-10T12:00:00Z"), "MANUAL"),
                Instant.parse("2026-01-10T12:00:30Z"));
    }

    @Test
    @DisplayName("Primeira liquidacao devolve 201, Location e os valores como string")
    void createsSettlement() throws Exception {
        given(settlementService.settle(any(SettleReceivableCommand.class)))
                .willReturn(new SettlementOutcome(settlement(), false));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/receivables/" + RECEIVABLE_ID + "/settlement"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.settlementAmount.amount").value("17094.67"))
                .andExpect(jsonPath("$.settlementAmount.currency").value("USD"))
                .andExpect(jsonPath("$.presentValue.amount").value("92859.94"))
                .andExpect(jsonPath("$.fxRate.rate").value("5.4321"))
                .andExpect(jsonPath("$.fxRate.baseCurrency").value("USD"));
    }

    @Test
    @DisplayName("Retry da mesma chave devolve 200 (nao 201): nada novo foi criado")
    void replaysSettlement() throws Exception {
        given(settlementService.settle(any(SettleReceivableCommand.class)))
                .willReturn(new SettlementOutcome(settlement(), true));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.id").value("22222222-2222-2222-2222-222222222222"));
    }

    @Test
    @DisplayName("Sem Idempotency-Key a requisicao e recusada com 400")
    void rejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    @DisplayName("Chave reusada com outro payload e 409, nao repeticao silenciosa")
    void mapsIdempotencyConflictToConflict() throws Exception {
        willThrow(new IdempotencyConflictException("key-1"))
                .given(settlementService).settle(any(SettleReceivableCommand.class));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("Recebivel ja liquidado e 409")
    void mapsAlreadySettledToConflict() throws Exception {
        willThrow(new ReceivableNotSettleableException(RECEIVABLE_ID, ReceivableStatus.SETTLED))
                .given(settlementService).settle(any(SettleReceivableCommand.class));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Corrida perdida no optimistic locking e 409")
    void mapsConcurrencyToConflict() throws Exception {
        willThrow(new ConcurrentSettlementException(RECEIVABLE_ID, new RuntimeException("versao desatualizada")))
                .given(settlementService).settle(any(SettleReceivableCommand.class));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_SETTLEMENT"));
    }

    @Test
    @DisplayName("Recebivel inexistente e 404")
    void mapsUnknownReceivableToNotFound() throws Exception {
        willThrow(new ReceivableNotFoundException(RECEIVABLE_ID))
                .given(settlementService).settle(any(SettleReceivableCommand.class));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Provedor de cambio fora do ar e 503: nada foi gravado, o cliente pode repetir")
    void mapsFxOutageToServiceUnavailable() throws Exception {
        willThrow(new FxRateUnavailableException(Currency.USD, Currency.BRL, "sem cotacao vigente"))
                .given(settlementService).settle(any(SettleReceivableCommand.class));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("FX_RATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Payload sem recebivel e 400 com o campo violado nomeado")
    void rejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementCurrency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.receivableId").exists());
    }

    @Test
    @DisplayName("Moeda desconhecida no corpo e 400, nao 500")
    void rejectsUnknownCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "key-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receivableId":"11111111-1111-1111-1111-111111111111","settlementCurrency":"EUR"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }
}
