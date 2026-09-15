package br.com.srm.creditengine.api;

import br.com.srm.creditengine.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo pela API contra PostgreSQL real: cedente, recebivel, cotacao, simulacao,
 * liquidacao e extrato.
 *
 * <p>Vale o golden case C3 ponta a ponta: o valor que sai no JSON precisa ser
 * {@code US$ 17.094,67}, com o prazo derivado do vencimento enviado. Se algum ponto da
 * cadeia (serializacao, conversao de string, banco) perder precisao, e aqui que aparece.
 */
@AutoConfigureMockMvc
class CreditEngineApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    private String dueDate;

    @BeforeEach
    void setUp() {
        truncateAll();
        // 90 dias = 3 meses comerciais, o prazo dos golden cases C1 e C3
        dueDate = LocalDate.ofInstant(clock.instant(), clock.getZone()).plusDays(90).toString();
    }

    private String createAssignor(String document) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assignors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"document":"%s","legalName":"Industria Ipiranga Ltda"}
                                """.formatted(document)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document").value(document))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String createReceivable(String assignorId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/receivables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignorId":"%s","type":"DUPLICATA_MERCANTIL","faceValue":"100000.00",
                                 "faceCurrency":"BRL","dueDate":"%s"}
                                """.formatted(assignorId, dueDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.faceValue.amount").value("100000.00"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void registerUsdRate(String rate) throws Exception {
        mockMvc.perform(post("/api/v1/fx-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"BRL","rate":"%s","source":"MESA_OPERACOES"}
                                """.formatted(rate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.source").value("MESA_OPERACOES"));
    }

    @Test
    @DisplayName("Fluxo do operador: cadastra, simula, liquida em USD e encontra no extrato (C3 via HTTP)")
    void fullOperatorFlow() throws Exception {
        String assignorId = createAssignor("12345678000199");
        String receivableId = createReceivable(assignorId);
        registerUsdRate("5.4321");

        // simulacao: o numero da tela e o numero que a liquidacao vai gravar
        mockMvc.perform(post("/api/v1/simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"faceValue":"100000.00","faceCurrency":"BRL","type":"DUPLICATA_MERCANTIL",
                                 "dueDate":"%s","settlementCurrency":"USD"}
                                """.formatted(dueDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termMonths").value(3))
                .andExpect(jsonPath("$.presentValue.amount").value("92859.94"))
                .andExpect(jsonPath("$.settlementAmount.amount").value("17094.67"))
                .andExpect(jsonPath("$.settlementAmount.currency").value("USD"))
                .andExpect(jsonPath("$.discount.amount").value("7140.06"))
                .andExpect(jsonPath("$.fxRate.rate").value("5.432100"));

        String body = """
                {"receivableId":"%s","settlementCurrency":"USD"}
                """.formatted(receivableId);

        MvcResult created = mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "chave-do-operador-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.settlementAmount.amount").value("17094.67"))
                .andExpect(jsonPath("$.presentValue.amount").value("92859.94"))
                .andExpect(jsonPath("$.fxRate.baseCurrency").value("USD"))
                .andReturn();
        String settlementId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // retry do operador (duplo clique): mesma chave, mesmo corpo -> 200 e a original
        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "chave-do-operador-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.id").value(settlementId));

        // o recebivel ficou marcado e a versao avancou (optimistic locking)
        mockMvc.perform(get("/api/v1/receivables/{id}", receivableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.version").value(1));

        // auditoria consultavel pela cotacao efetivamente aplicada
        mockMvc.perform(get("/api/v1/receivables/{id}/settlement", receivableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(settlementId))
                .andExpect(jsonPath("$.idempotencyKey").value("chave-do-operador-1"))
                .andExpect(jsonPath("$.fxRate.source").value("MESA_OPERACOES"));

        // extrato filtrado por cedente e moeda, com total do periodo
        mockMvc.perform(get("/api/v1/settlements/statement")
                        .param("assignorId", assignorId)
                        .param("currency", "USD")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].assignorLegalName").value("Industria Ipiranga Ltda"))
                .andExpect(jsonPath("$.content[0].receivableType").value("DUPLICATA_MERCANTIL"))
                .andExpect(jsonPath("$.content[0].settlementAmount.amount").value("17094.67"))
                .andExpect(jsonPath("$.totals[0].settlementAmount.amount").value("17094.67"));
    }

    @Test
    @DisplayName("Liquidar recebivel ja liquidado com chave nova responde 409, nao 200")
    void rejectsSecondSettlement() throws Exception {
        String assignorId = createAssignor("12345678000199");
        String receivableId = createReceivable(assignorId);

        String body = """
                {"receivableId":"%s","settlementCurrency":"BRL"}
                """.formatted(receivableId);

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "chave-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementAmount.amount").value("92859.94"));

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "chave-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIVABLE_NOT_SETTLEABLE"));
    }

    @Test
    @DisplayName("Liquidacao em USD sem cotacao vigente responde 503 e nao grava nada")
    void rejectsSettlementWithoutFxRate() throws Exception {
        String assignorId = createAssignor("12345678000199");
        String receivableId = createReceivable(assignorId);

        mockMvc.perform(post("/api/v1/settlements")
                        .header("Idempotency-Key", "chave-sem-cambio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receivableId":"%s","settlementCurrency":"USD"}
                                """.formatted(receivableId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("FX_RATE_UNAVAILABLE"));

        mockMvc.perform(get("/api/v1/receivables/{id}", receivableId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/v1/receivables/{id}/settlement", receivableId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SETTLEMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Documento de cedente duplicado responde 409")
    void rejectsDuplicateAssignor() throws Exception {
        createAssignor("12345678000199");

        mockMvc.perform(post("/api/v1/assignors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"document":"12345678000199","legalName":"Outra Razao Social"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSIGNOR_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("Recebivel de cedente inexistente responde 404 e vencimento no passado, 422")
    void validatesReceivableRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/receivables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignorId":"00000000-0000-0000-0000-000000000000","type":"DUPLICATA_MERCANTIL",
                                 "faceValue":"100000.00","faceCurrency":"BRL","dueDate":"%s"}
                                """.formatted(dueDate)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ASSIGNOR_NOT_FOUND"));

        String assignorId = createAssignor("12345678000199");
        String pastDate = LocalDate.ofInstant(clock.instant(), clock.getZone()).minusDays(1).toString();

        mockMvc.perform(post("/api/v1/receivables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignorId":"%s","type":"DUPLICATA_MERCANTIL","faceValue":"100000.00",
                                 "faceCurrency":"BRL","dueDate":"%s"}
                                """.formatted(assignorId, pastDate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.dueDate").exists());
    }

    @Test
    @DisplayName("Cotacao repetida na mesma vigencia responde 409 e o historico continua consultavel")
    void keepsFxHistoryAppendOnly() throws Exception {
        String effectiveAt = clock.instant().minusSeconds(120).toString();

        mockMvc.perform(post("/api/v1/fx-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"BRL","rate":"5.4321","effectiveAt":"%s"}
                                """.formatted(effectiveAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"));

        mockMvc.perform(post("/api/v1/fx-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"USD","quoteCurrency":"BRL","rate":"9.9999","effectiveAt":"%s"}
                                """.formatted(effectiveAt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("FX_RATE_ALREADY_REGISTERED"));

        mockMvc.perform(get("/api/v1/fx-rates/current").param("base", "USD").param("quote", "BRL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value("5.432100"));

        mockMvc.perform(get("/api/v1/fx-rates").param("base", "USD").param("quote", "BRL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("Cotacao com moedas iguais responde 422, nao 500")
    void rejectsSameCurrencyRate() throws Exception {
        mockMvc.perform(post("/api/v1/fx-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseCurrency":"BRL","quoteCurrency":"BRL","rate":"1.0"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("A especificacao OpenAPI e gerada e descreve os endpoints principais")
    void exposesOpenApiDocument() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String document = result.getResponse().getContentAsString();
        assertThat(document)
                .contains("/api/v1/settlements")
                .contains("/api/v1/settlements/statement")
                .contains("/api/v1/simulations")
                .contains("Idempotency-Key");
    }
}
