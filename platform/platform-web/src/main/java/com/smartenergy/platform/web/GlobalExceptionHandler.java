package com.smartenergy.platform.web;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.error.FieldViolation;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Toutes les erreurs REST sortent en application/problem+json (RFC 9457) avec les extensions
 * "code" (enum ErrorCode), "traceId" et, pour les 400 de validation, "errors[]".
 * Les exceptions standard de Spring MVC (404, 405, 415, parametres...) passent par la classe de base
 * et sont enrichies dans {@link #handleExceptionInternal}.
 *
 * <p>{@code @ControllerAdvice} est INDISPENSABLE : sans lui, Spring MVC ignore les
 * {@code @ExceptionHandler} de ce bean (enregistre pourtant par la platform-web
 * auto-configuration) et chaque service retombe sur l'erreur JSON par defaut de Spring Boot.
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @org.springframework.web.bind.annotation.ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        ProblemDetail pd = problem(ex.code(), ex.getMessage(), ex.violations());
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        return ResponseEntity.status(ex.code().status()).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return handleApi(new ApiException(ErrorCode.VERSION_CONFLICT,
                "La ressource a ete modifiee entre-temps : relisez-la puis reessayez"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Erreur non geree", ex);
        return handleApi(new ApiException(ErrorCode.INTERNAL_ERROR, "Une erreur interne est survenue"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        String detail = violations.size() + " champ(s) invalide(s)";
        return handleExceptionInternal(ex, problem(ErrorCode.VALIDATION_ERROR, detail, violations),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex,
                problem(ErrorCode.MALFORMED_JSON, "Corps JSON illisible ou de type incorrect", List.of()),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Enrichit le Problem Details avec "code" et "traceId", et aligne "type" (URI RFC 9457 de
     * l'ErrorCode du statut au lieu de about:blank) et "title" (titre francais de l'ErrorCode
     * au lieu de la raison phrase anglaise de Spring) sur le reste des erreurs de la plateforme.
     *
     * <p>L'ordre compte : les sous-methodes de la classe de base (404 NoResourceFoundException,
     * 405, 415, timeout asynchrone...) passent {@code body = null} et c'est
     * {@code super.handleExceptionInternal} qui construit ensuite le ProblemDetail (via
     * {@code ErrorResponse.updateAndGetBody}). Un test sur {@code body} AVANT l'appel ne voit
     * donc jamais ces corps ; on enrichit ce que la methode de base a produit.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders h = new HttpHeaders();
        if (headers != null) h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, h, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            ErrorCode code = defaultCodeFor(statusCode.value());
            if (pd.getType() == null || URI.create("about:blank").equals(pd.getType())) {
                pd.setType(URI.create(code.typeUri()));
            }
            // Titre par defaut de Spring = raison phrase anglaise ("Not Found") : on le remplace
            // par celui de l'ErrorCode pour rester identique aux erreurs produites par problem().
            String raisonPhrase = HttpStatus.valueOf(statusCode.value()).getReasonPhrase();
            if (pd.getTitle() == null || pd.getTitle().isBlank() || pd.getTitle().equals(raisonPhrase)) {
                pd.setTitle(code.title());
            }
            pd.setProperty("code", code.name());
            pd.setProperty("traceId", RequestIdFilter.currentRequestId());
        }
        return response;
    }

    // ------------------------------------------------------------------ utilitaires

    private static ProblemDetail problem(ErrorCode code, String detail, List<FieldViolation> violations) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        pd.setProperty("traceId", RequestIdFilter.currentRequestId());
        if (violations != null && !violations.isEmpty()) pd.setProperty("errors", violations);
        return pd;
    }

    private static ErrorCode defaultCodeFor(int status) {
        return switch (status) {
            case 400 -> ErrorCode.INVALID_PARAMETER;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
