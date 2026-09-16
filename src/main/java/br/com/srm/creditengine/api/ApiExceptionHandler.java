package br.com.srm.creditengine.api;

import br.com.srm.creditengine.domain.DomainException;
import br.com.srm.creditengine.domain.assignor.AssignorAlreadyRegisteredException;
import br.com.srm.creditengine.domain.assignor.AssignorNotFoundException;
import br.com.srm.creditengine.domain.fx.FxRateAlreadyRegisteredException;
import br.com.srm.creditengine.domain.fx.FxRateNotApplicableException;
import br.com.srm.creditengine.domain.fx.FxRateUnavailableException;
import br.com.srm.creditengine.domain.money.CurrencyMismatchException;
import br.com.srm.creditengine.domain.pricing.InvalidPricingInputException;
import br.com.srm.creditengine.domain.pricing.MissingFxRateException;
import br.com.srm.creditengine.domain.pricing.UnsupportedReceivableTypeException;
import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.receivable.ReceivableNotFoundException;
import br.com.srm.creditengine.domain.receivable.ReceivableNotSettleableException;
import br.com.srm.creditengine.domain.settlement.IdempotencyConflictException;
import br.com.srm.creditengine.domain.settlement.SettlementInProgressException;
import br.com.srm.creditengine.domain.settlement.SettlementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tradutor unico de erro em resposta HTTP (RFC 9457 - {@code application/problem+json}).
 *
 * <p>Existe para eliminar dois anti-padroes de uma vez: <b>nenhum</b> erro sai com
 * {@code 200} e <b>nenhuma</b> excecao e engolida em silencio - 4xx viram log de aviso
 * (sem stack, e culpa do pedido) e 5xx viram log de erro com a causa completa.
 *
 * <p>O mapeamento de excecao de dominio para status fica concentrado em
 * {@link #statusFor(DomainException)}: e a tabela que responde "o que o cliente deve fazer
 * com este erro" - corrigir o pedido (4xx), desistir (409) ou tentar de novo (503).
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} e necessario: com
 * {@code spring.mvc.problemdetails.enabled=true} o Boot registra o proprio advice para as
 * excecoes de framework, e sem a precedencia explicita ele venceria - a resposta sairia
 * sem o campo {@code errorCode} e sem os campos violados.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException exception) {
        HttpStatus status = statusFor(exception);

        if (status.is5xxServerError()) {
            log.error("Erro de dominio tratado como falha do servidor: errorCode={}",
                    exception.errorCode(), exception);
        } else {
            log.warn("Requisicao recusada: errorCode={} status={} detail={}",
                    exception.errorCode(), status.value(), exception.getMessage());
        }

        return problem(status, exception.errorCode(), exception.getMessage());
    }

    /**
     * Mapa de dominio -> HTTP.
     *
     * <ul>
     *   <li><b>404</b>: recurso inexistente;</li>
     *   <li><b>409</b>: pedido valido que colide com o estado atual (ja liquidado, chave de
     *       idempotencia reusada, corrida perdida no optimistic locking) - repetir igual nao
     *       resolve. A excecao e {@code SETTLEMENT_IN_PROGRESS}: ali repetir resolve, e a
     *       repeticao devolve a liquidacao original;</li>
     *   <li><b>422</b>: pedido sintaticamente valido mas sem sentido financeiro;</li>
     *   <li><b>503</b>: dependencia indisponivel (cotacao ausente ou defasada) - vale
     *       tentar de novo mais tarde;</li>
     *   <li><b>500</b>: invariante do proprio sistema violada (moeda incoerente, tipo sem
     *       strategy) - e bug nosso, nao erro do cliente.</li>
     * </ul>
     */
    private static HttpStatus statusFor(DomainException exception) {
        return switch (exception) {
            case ReceivableNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case AssignorNotFoundException ignored -> HttpStatus.NOT_FOUND;
            case SettlementNotFoundException ignored -> HttpStatus.NOT_FOUND;

            case ReceivableNotSettleableException ignored -> HttpStatus.CONFLICT;
            case ConcurrentSettlementException ignored -> HttpStatus.CONFLICT;
            case IdempotencyConflictException ignored -> HttpStatus.CONFLICT;
            case SettlementInProgressException ignored -> HttpStatus.CONFLICT;
            case AssignorAlreadyRegisteredException ignored -> HttpStatus.CONFLICT;
            case FxRateAlreadyRegisteredException ignored -> HttpStatus.CONFLICT;

            case FxRateUnavailableException ignored -> HttpStatus.SERVICE_UNAVAILABLE;

            case InvalidPricingInputException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MissingFxRateException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case FxRateNotApplicableException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;

            case CurrencyMismatchException ignored -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UnsupportedReceivableTypeException ignored -> HttpStatus.INTERNAL_SERVER_ERROR;

            // Excecao de dominio nova sem mapeamento: 422 e o default menos perigoso
            // (recusa a operacao) e o log aponta o que falta mapear.
            default -> {
                log.warn("DomainException sem mapeamento explicito de status: {}",
                        exception.getClass().getName());
                yield HttpStatus.UNPROCESSABLE_ENTITY;
            }
        };
    }

    /** Corpo invalido segundo Bean Validation: devolve campo a campo, nao uma mensagem vaga. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> violations.put(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> violations.put(error.getObjectName(), error.getDefaultMessage()));

        log.warn("Payload invalido: {}", violations);

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Payload invalido: corrija os campos indicados");
        problem.setProperty("violations", violations);
        return problem;
    }

    /** Validacao de parametro ou header (ex.: {@code Idempotency-Key} em branco). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleInvalidParameters(HandlerMethodValidationException exception) {
        log.warn("Parametro invalido: {}", exception.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Parametro ou header invalido na requisicao");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
        log.warn("Header obrigatorio ausente: {}", exception.getHeaderName());
        return problem(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
                "Header obrigatorio ausente: " + exception.getHeaderName());
    }

    /**
     * JSON malformado, enum desconhecido ou data em formato invalido.
     *
     * <p>A mensagem original do Jackson nao vai para o cliente: ela expoe nome de classe e
     * estrutura interna.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.warn("Corpo ilegivel: {}", exception.getMostSpecificCause().getMessage());
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Corpo da requisicao ilegivel ou com valor nao reconhecido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Parametro com tipo invalido: name={} value={}", exception.getName(), exception.getValue());
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Valor invalido para o parametro '%s'".formatted(exception.getName()));
    }

    /**
     * Invariante de construcao violada (ex.: cotacao com moedas iguais ou taxa negativa).
     *
     * <p>{@code 422} e nao {@code 500}: o pedido chegou bem formado, mas descreve algo que
     * nao existe no negocio. Estes casos vivem em {@code IllegalArgumentException} porque
     * sao guardas de construtor de value object, nao regra de fluxo.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("Argumento invalido: {}", exception.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ARGUMENT", exception.getMessage());
    }

    /**
     * Falha de banco que nao virou erro de dominio.
     *
     * <p>{@code 503} e nao {@code 500}: a operacao nao aconteceu (a transacao foi desfeita)
     * e repetir o pedido com a mesma chave de idempotencia e seguro.
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccess(DataAccessException exception) {
        log.error("Falha de acesso a dados", exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE",
                "Falha temporaria de persistencia; repita a requisicao com a mesma Idempotency-Key");
    }

    /** Ultima rede: registra a causa completa e devolve 500 sem vazar detalhe interno. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("Erro nao tratado", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Erro interno ao processar a requisicao");
    }

    private static ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        // Codigo estavel para o cliente tratar programaticamente e para agregar em metricas.
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
