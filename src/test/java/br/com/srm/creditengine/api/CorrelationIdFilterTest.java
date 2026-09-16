package br.com.srm.creditengine.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que este teste protege e a utilidade do log num incidente.
 *
 * <p>Correlacao que nao chega ao MDC nao aparece no JSON, e nao aparecer no JSON significa
 * que ninguem consegue isolar as linhas de uma liquidacao especifica no meio do trafego -
 * exatamente a tarefa do plantao no incidente do Anexo B. Os dois casos que mais importam
 * aqui sao os negativos: valor forjado pelo cliente nao entra no log, e contexto de uma
 * requisicao nao vaza para a proxima que reaproveitar a thread.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final Map<String, String> observedContext = new HashMap<>();

    @Test
    @DisplayName("Gera correlacao quando o cliente nao manda, e devolve no header da resposta")
    void generatesCorrelationIdWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, observing());

        String logged = observedContext.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        assertThat(logged).isNotBlank();
        assertThat(UUID.fromString(logged)).isNotNull();
        // O cliente precisa receber o mesmo id que foi para o log: e com ele em maos que
        // alguem abre ticket e o plantao acha a linha exata.
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(logged);
    }

    @Test
    @DisplayName("Reaproveita a correlacao enviada pelo cliente para o rastro atravessar a fronteira")
    void reusesCorrelationIdFromCaller() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "mesa-op-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, observing());

        assertThat(observedContext).containsEntry(CorrelationIdFilter.CORRELATION_ID_KEY, "mesa-op-42");
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("mesa-op-42");
    }

    @Test
    @DisplayName("Chave de idempotencia entra no contexto de log")
    void putsIdempotencyKeyInContext() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CorrelationIdFilter.IDEMPOTENCY_KEY_HEADER, "op-2024-03-15-0001");

        filter.doFilter(request, new MockHttpServletResponse(), observing());

        // "Todas as tentativas desta chave" e a consulta que separa retry legitimo de
        // pagamento duplicado. Ela so existe se a chave for campo de log.
        assertThat(observedContext)
                .containsEntry(CorrelationIdFilter.IDEMPOTENCY_KEY_KEY, "op-2024-03-15-0001");
    }

    @Test
    @DisplayName("Header forjado com quebra de linha e descartado em vez de virar linha de log")
    void rejectsForgedHeader() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER,
                "abc\n{\"level\":\"INFO\",\"message\":\"liquidacao concluida\"}");
        request.addHeader(CorrelationIdFilter.IDEMPOTENCY_KEY_HEADER, "chave com espaco e aspas \"");

        filter.doFilter(request, new MockHttpServletResponse(), observing());

        // Valor de terceiro nao entra no log: quebra de linha permitiria fabricar um
        // evento inteiro e contaminar a investigacao de um incidente de pagamento.
        String logged = observedContext.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        assertThat(logged).doesNotContain("liquidacao concluida");
        assertThat(UUID.fromString(logged)).isNotNull();
        assertThat(observedContext).doesNotContainKey(CorrelationIdFilter.IDEMPOTENCY_KEY_KEY);
    }

    @Test
    @DisplayName("Contexto e limpo no fim, mesmo quando a requisicao falha")
    void clearsContextEvenOnFailure() {
        MockHttpServletRequest request = request();
        request.addHeader(CorrelationIdFilter.IDEMPOTENCY_KEY_HEADER, "op-que-falhou");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("falha no meio da liquidacao");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("falha no meio da liquidacao");

        // Thread de pool atende a proxima requisicao: sobra no MDC faria um pagamento
        // aparecer no log com a chave de idempotencia de outro.
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.IDEMPOTENCY_KEY_KEY)).isNull();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/settlements");
    }

    /** Fotografa o MDC de dentro da cadeia: e ali que o log da requisicao acontece. */
    private FilterChain observing() {
        return (req, res) -> {
            putIfPresent(CorrelationIdFilter.CORRELATION_ID_KEY);
            putIfPresent(CorrelationIdFilter.IDEMPOTENCY_KEY_KEY);
        };
    }

    private void putIfPresent(String key) {
        String value = MDC.get(key);
        if (value != null) {
            observedContext.put(key, value);
        }
    }
}
