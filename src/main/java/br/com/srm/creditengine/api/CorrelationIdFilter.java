package br.com.srm.creditengine.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Da a cada requisicao um identificador de correlacao e o coloca no contexto de log.
 *
 * <p>Sem isso, log de sistema financeiro e inutil no plantao: as linhas de uma mesma
 * liquidacao ficam intercaladas com as de outras dezenas de requisicoes, e a pergunta que
 * importa - "o que aconteceu NESTA tentativa de pagamento?" - nao tem resposta. Com
 * {@code correlationId} e {@code idempotencyKey} no MDC, as duas perguntas do plantao
 * viram filtro no agregador: pela requisicao e pela chave que o cliente repetiu.
 *
 * <p>O identificador vem do cliente quando ele manda {@value #CORRELATION_ID_HEADER} -
 * assim o rastro atravessa a fronteira de quem chamou - e e devolvido sempre no cabecalho
 * da resposta, inclusive em erro. Quem abrir um ticket com o id da resposta em maos acha a
 * linha exata no log.
 *
 * <p><b>Valor de terceiro nao entra no log sem passar por filtro.</b> Cabecalho e dado
 * controlado pelo chamador; aceita-lo cru permitiria injetar quebra de linha e forjar
 * linhas de log inteiras. Aqui so passa o que casa com {@link #SAFE_VALUE}; qualquer outra
 * coisa e substituida por um id gerado em casa, sem reclamar - o objetivo e rastrear, nao
 * validar contrato.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static final String CORRELATION_ID_KEY = "correlationId";
    static final String IDEMPOTENCY_KEY_KEY = "idempotencyKey";

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,120}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitized(request.getHeader(CORRELATION_ID_HEADER))
                .orElseGet(() -> UUID.randomUUID().toString());

        MDC.put(CORRELATION_ID_KEY, correlationId);
        sanitized(request.getHeader(IDEMPOTENCY_KEY_HEADER))
                .ifPresent(key -> MDC.put(IDEMPOTENCY_KEY_KEY, key));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Remove apenas as chaves proprias: o MDC vive na thread, e thread de pool
            // atende a proxima requisicao. Deixar sobra faz um pagamento aparecer no log
            // com a chave de idempotencia de outro - pior que nao ter log nenhum.
            MDC.remove(CORRELATION_ID_KEY);
            MDC.remove(IDEMPOTENCY_KEY_KEY);
        }
    }

    private Optional<String> sanitized(String value) {
        if (value == null || !SAFE_VALUE.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
