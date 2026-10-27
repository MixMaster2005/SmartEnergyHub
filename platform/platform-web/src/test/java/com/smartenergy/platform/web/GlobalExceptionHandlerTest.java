package com.smartenergy.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tests unitaires (sans contexte Spring) du GlobalExceptionHandler : les exceptions standard de
 * Spring MVC passent par ResponseEntityExceptionHandler avec un corps NULL, c'est la methode de
 * base qui construit ensuite le ProblemDetail — l'enrichissement "code"/"traceId" doit donc se
 * faire APRES super.handleExceptionInternal.
 */
class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "trace-handler-test";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inconnu");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void fixer_trace_id() {
        MDC.put(RequestIdFilter.MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void nettoyer_mdc() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    /** Passe par la resoluseur reelle (handleException final de la classe de base). */
    private ResponseEntity<Object> gerer(Exception ex) throws Exception {
        ResponseEntity<Object> entity = handler.handleException(ex, new ServletWebRequest(request, response));
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(entity.getBody()).isInstanceOf(ProblemDetail.class);
        return entity;
    }

    /** Corps Problem Details d'une reponse geree. */
    private static ProblemDetail corps(ResponseEntity<Object> entity) {
        return (ProblemDetail) entity.getBody();
    }

    private static void verifier_extension(ProblemDetail pd, ErrorCode attendu) {
        assertThat(pd.getStatus()).isEqualTo(attendu.status());
        assertThat(pd.getType()).hasToString(attendu.typeUri());
        assertThat(pd.getTitle()).isEqualTo(attendu.title());
        assertThat(pd.getProperties()).containsEntry("code", attendu.name());
        assertThat(pd.getProperties()).containsEntry("traceId", TRACE_ID);
    }

    @Test
    void route_inconnue_404_recit_code_et_trace_id() throws Exception {
        ProblemDetail pd = corps(gerer(new NoResourceFoundException(org.springframework.http.HttpMethod.GET,
                "/api/inconnu")));

        verifier_extension(pd, ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(pd.getDetail()).isNotBlank();
    }

    @Test
    void methode_non_allowee_405_recit_code_et_trace_id() throws Exception {
        ResponseEntity<Object> entity = gerer(new HttpRequestMethodNotSupportedException("DELETE",
                List.of("GET", "POST")));

        verifier_extension(corps(entity), ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("GET");
    }

    @Test
    void content_type_non_supporte_415_recit_code_et_trace_id() throws Exception {
        ProblemDetail pd = corps(gerer(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                List.of(MediaType.APPLICATION_JSON))));

        verifier_extension(pd, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void code_et_trace_id_deja_presents_ne_sont_pas_ecrases() throws Exception {
        ProblemDetail signe = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "detail maison");
        signe.setType(URI.create(ErrorCode.VALIDATION_ERROR.typeUri()));
        signe.setProperty("code", "VALIDATION_ERROR");
        signe.setProperty("traceId", "trace-autre");
        signe.setProperty("errors", List.of("champ"));

        ResponseEntity<Object> entity = handler.handleExceptionInternal(
                new IllegalArgumentException("x"), signe, new HttpHeaders(), HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request, response));

        ProblemDetail pd = (ProblemDetail) entity.getBody();
        assertThat(pd.getProperties()).containsEntry("code", "VALIDATION_ERROR");
        assertThat(pd.getProperties()).containsEntry("traceId", "trace-autre");
        assertThat(pd.getProperties()).containsEntry("errors", List.of("champ"));
    }

    @Test
    void exception_api_conservation_du_corps_signe() {
        ResponseEntity<Object> entity = handler.handleApi(
                ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE, "Corps trop volumineux"));

        assertThat(entity.getStatusCode().value()).isEqualTo(413);
        ProblemDetail pd = (ProblemDetail) entity.getBody();
        assertThat(pd.getType()).hasToString(ErrorCode.PAYLOAD_TOO_LARGE.typeUri());
        assertThat(pd.getProperties()).containsEntry("code", "PAYLOAD_TOO_LARGE");
        assertThat(pd.getProperties()).containsEntry("traceId", TRACE_ID);
    }
}
