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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Toutes les erreurs REST sortent en application/problem+json (RFC 9457) avec les extensions
 * "code" (enum ErrorCode), "traceId" et, pour les 400 de validation, "errors[]".
 * Les exceptions standard de Spring MVC (404, 405, 415, parametres...) passent par la classe de base
 * et sont enrichies dans {@link #handleExceptionInternal}.
 */
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

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            ErrorCode code = defaultCodeFor(statusCode.value());
            pd.setType(URI.create(typeUri(code)));
            pd.setProperty("code", code.name());
            pd.setProperty("traceId", RequestIdFilter.currentRequestId());
        }
        HttpHeaders h = new HttpHeaders();
        if (headers != null) h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(ex, body, h, statusCode, request);
    }

    // ------------------------------------------------------------------ utilitaires

    private static ProblemDetail problem(ErrorCode code, String detail, List<FieldViolation> violations) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        pd.setType(URI.create(typeUri(code)));
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        pd.setProperty("traceId", RequestIdFilter.currentRequestId());
        if (violations != null && !violations.isEmpty()) pd.setProperty("errors", violations);
        return pd;
    }

    private static String typeUri(ErrorCode code) {
        return "https://smartenergy.local/problems/" + code.slug();
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
