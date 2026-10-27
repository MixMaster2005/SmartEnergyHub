package com.smartenergy.collector.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests unitaires (sans Spring ni MockMvc) de la limite de taille du corps : chemin
 * Content-Length, corps chunked sans Content-Length, portee POST /api/measurements.
 */
class BodySizeLimitFilterTest {

    private static final long MAX_BODY_BYTES = 10240;
    private static final String TRACE_ID = "trace-test";
    private static final String URI_MESURES = "/api/measurements";

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final BodySizeLimitFilter filter = new BodySizeLimitFilter(
            new MeasurementProperties(Duration.ofHours(24), Duration.ofMinutes(5), MAX_BODY_BYTES), mapper);

    @BeforeEach
    void fixer_trace_id() {
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void nettoyer_mdc() {
        MDC.remove("traceId");
    }

    /** Corps JSON fictif de la taille demandee (le contenu n'est pas lu par le filtre). */
    private static byte[] corps_de(int octets) {
        byte[] corps = new byte[octets];
        Arrays.fill(corps, (byte) 'a');
        return corps;
    }

    /** Requete qui annonce un corps mais pas de Content-Length (equivalent chunked). */
    private static MockHttpServletRequest chunked(String method, String uri, byte[] corps) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(corps);
        return request;
    }

    /** Chaine qui lit tout le corps : c'est la lecture qui declenche la limite. */
    private static FilterChain lecteur_de_corps(AtomicInteger octetsLus) {
        return (request, response) -> {
            try {
                octetsLus.addAndGet(request.getInputStream().readAllBytes().length);
            } catch (IOException ex) {
                throw new ServletException(ex);
            }
        };
    }

    @Test
    void content_length_trop_grand_renvoie_413_sans_appeler_la_chaine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI_MESURES);
        request.setContent(corps_de((int) MAX_BODY_BYTES + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(chain.getRequest()).isNull();

        JsonNode problem = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.get("type").asText()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.typeUri());
        assertThat(problem.get("title").asText()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.title());
        assertThat(problem.get("status").asInt()).isEqualTo(413);
        assertThat(problem.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(problem.get("detail").asText()).contains(String.valueOf(MAX_BODY_BYTES));
        assertThat(problem.get("instance").asText()).isEqualTo(URI_MESURES);
        assertThat(problem.get("traceId").asText()).isEqualTo(TRACE_ID);
    }

    @Test
    void content_length_de_10240_octets_exactement_est_accepte() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI_MESURES);
        request.setContent(corps_de((int) MAX_BODY_BYTES));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void corps_chunked_trop_grand_renvoie_413() throws Exception {
        MockHttpServletRequest request = chunked("POST", URI_MESURES, corps_de((int) MAX_BODY_BYTES + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            try {
                req.getInputStream().readAllBytes();
            } catch (IOException ex) {
                throw new ServletException(ex);
            }
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(problem.get("traceId").asText()).isEqualTo(TRACE_ID);
    }

    @Test
    void corps_chunked_dans_la_limite_est_lu_integralement() throws Exception {
        MockHttpServletRequest request = chunked("POST", URI_MESURES, corps_de(512));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger octetsLus = new AtomicInteger();

        filter.doFilter(request, response, lecteur_de_corps(octetsLus));

        assertThat(octetsLus.get()).isEqualTo(512);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void post_hors_chemin_et_autres_methodes_ignore_le_filtre() throws Exception {
        MockHttpServletRequest autreRoute = new MockHttpServletRequest("POST", "/api/sensors");
        autreRoute.setContent(corps_de((int) MAX_BODY_BYTES + 1));
        MockHttpServletResponse reponseAutreRoute = new MockHttpServletResponse();
        MockFilterChain chaineAutreRoute = new MockFilterChain();

        filter.doFilter(autreRoute, reponseAutreRoute, chaineAutreRoute);

        assertThat(chaineAutreRoute.getRequest()).isSameAs(autreRoute);
        assertThat(reponseAutreRoute.getStatus()).isEqualTo(200);

        MockHttpServletRequest getSurMesures = new MockHttpServletRequest("GET", URI_MESURES);
        getSurMesures.setContent(corps_de((int) MAX_BODY_BYTES + 1));
        MockHttpServletResponse reponseGet = new MockHttpServletResponse();
        MockFilterChain chaineGet = new MockFilterChain();

        filter.doFilter(getSurMesures, reponseGet, chaineGet);

        assertThat(chaineGet.getRequest()).isSameAs(getSurMesures);
        assertThat(reponseGet.getStatus()).isEqualTo(200);
    }
}
