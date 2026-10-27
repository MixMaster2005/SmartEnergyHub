package com.smartenergy.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartenergy.contracts.common.HeaderNames;
import com.smartenergy.contracts.measurement.MeasurementFields;
import com.smartenergy.contracts.measurement.StreamContract;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration du collector : Redis jetable en Testcontainers, application complete
 * (@SpringBootTest, port aleatoire). CDC section 12, scenarios 1, 2, 8 et 9.
 *
 * <p>Chaque test verifie la REponse HTTP et l'EFFET de bord dans Redis Streams (XLEN, contenu du
 * stream) : la validation seule ne prouve pas que l'evenement est publie. Le dernier test arrete
 * Redis pour verifier le 503 + Retry-After ; il est place en fin de suite (ordonne) et remet le
 * conteneur en route en sortie.
 *
 * <p>Le contenu du stream est confronte aux champs requis de
 * {@code contracts/schemas/measurement-event.schema.json} (schemaVersion, eventId, traceId,
 * sensorId, timestamp, receivedAt, powerKw, voltage).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollectorIntegrationTest {

    /** Redis sans mot de passe : le mot de passe du {@code application.yml} est surcharge. */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    // --------------------------------------------------------------- scenario 1
    @Test
    @Order(1)
    void mesureValide_donne202_unEventId_etUnEvenementDansLeStream() {
        long avant = xlen();

        ResponseEntity<String> reponse = post(json("IT-COL-1", Instant.now().minusSeconds(5), "3.72"));

        assertThat(reponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reponse.getStatusCode().value()).isEqualTo(202);
        // en-tete X-Event-Id : identifiant deterministe (UUID v5) de l'evenement publie
        assertThat(reponse.getHeaders().getFirst(HeaderNames.EVENT_ID)).isNotBlank();
        assertThat(xlen()).isEqualTo(avant + 1);

        // le contenu du stream respecte le schema measurement-event.schema.json
        MapRecord<String, String, String> dernier = dernierEvenement();
        assertThat(dernier).isNotNull();
        assertThat(dernier.getValue()).containsKeys(
                MeasurementFields.SCHEMA_VERSION,
                MeasurementFields.EVENT_ID,
                MeasurementFields.TRACE_ID,
                MeasurementFields.SENSOR_ID,
                MeasurementFields.TIMESTAMP,
                MeasurementFields.RECEIVED_AT,
                MeasurementFields.POWER_KW,
                MeasurementFields.VOLTAGE);
        assertThat(dernier.getValue().get(MeasurementFields.SENSOR_ID)).isEqualTo("IT-COL-1");
        assertThat(dernier.getValue().get(MeasurementFields.EVENT_ID))
                .isEqualTo(reponse.getHeaders().getFirst(HeaderNames.EVENT_ID));
    }

    // --------------------------------------------------------------- scenario 2
    @Test
    @Order(2)
    void sensorIdAbsent_donne400ProblemDetails_etAucunEvenement() {
        long avant = xlen();

        ResponseEntity<String> reponse = post(
                "{\"timestamp\":\"" + Instant.now() + "\",\"powerKw\":3.72,\"voltage\":226.4}");

        assertThat(reponse.getStatusCode().value()).isEqualTo(400);
        assertThat(reponse.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(reponse.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(xlen()).isEqualTo(avant); // rien n'est publie
    }

    @Test
    @Order(3)
    void puissanceEnChaine_donne400MalformedJson_pasDeCoercition() {
        long avant = xlen();

        ResponseEntity<String> reponse = post(json("IT-COL-2", Instant.now(), "\"3.72\""));

        assertThat(reponse.getStatusCode().value()).isEqualTo(400);
        assertThat(reponse.getBody()).contains("\"code\":\"MALFORMED_JSON\"");
        assertThat(xlen()).isEqualTo(avant);
    }

    @Test
    @Order(4)
    void mesureTropAncienne_donne400() {
        long avant = xlen();

        ResponseEntity<String> reponse =
                post(json("IT-COL-3", Instant.now().minusSeconds(25 * 3600), "3.72"));

        assertThat(reponse.getStatusCode().value()).isEqualTo(400);
        assertThat(reponse.getBody()).contains("\"code\":\"MEASUREMENT_TOO_OLD\"");
        assertThat(xlen()).isEqualTo(avant);
    }

    // --------------------------------------------------------------- scenario 8
    @Test
    @Order(5)
    void doublon_deuxReponses202_memeEventId() {
        long avant = xlen();
        Instant mesure = Instant.now().minusSeconds(10);

        ResponseEntity<String> premier = post(json("IT-DUP-C", mesure, "5.0"));
        ResponseEntity<String> second = post(json("IT-DUP-C", mesure, "5.0"));

        assertThat(premier.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(202);
        // eventId deterministe (sensorId + timestamp) : le doublon porte le meme identifiant,
        // l'idempotence est assuree en aval (storage/ecriture, analytics/dedup).
        assertThat(second.getHeaders().getFirst(HeaderNames.EVENT_ID))
                .isEqualTo(premier.getHeaders().getFirst(HeaderNames.EVENT_ID));
        assertThat(xlen()).isEqualTo(avant + 2); // le collector publie ; c'est au consommateurs d'absorber
    }

    // --------------------------------------------------------------- scenario 9
    @Test
    @Order(6)
    void redisArrete_donne503_avecRetryAfter() {
        try {
            REDIS.stop();

            ResponseEntity<String> reponse = post(json("IT-9-1", Instant.now(), "3.72"));

            assertThat(reponse.getStatusCode().value()).isEqualTo(503);
            assertThat(reponse.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(reponse.getBody()).contains("\"code\":\"BROKER_UNAVAILABLE\"");
        } finally {
            REDIS.start(); // remise en route pour la suite (conteneur reutilise)
        }
    }

    // --------------------------------------------------------------- helpers
    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/measurements", HttpMethod.POST, new HttpEntity<>(json, headers),
                String.class);
    }

    private static String json(String sensorId, Instant timestamp, String powerKw) {
        return "{\"sensorId\":\"" + sensorId + "\",\"timestamp\":\"" + timestamp
                + "\",\"powerKw\":" + powerKw + ",\"voltage\":226.4}";
    }

    /** XLEN du stream de mesure ; 0 si le stream n'existe pas encore. */
    private long xlen() {
        Long size = redis.opsForStream().size(StreamContract.STREAM);
        return size == null ? 0 : size;
    }

    private MapRecord<String, String, String> dernierEvenement() {
        StreamOperations<String, String, String> flux = redis.opsForStream();
        Range<String> touteLaPlage = Range.unbounded();
        List<MapRecord<String, String, String>> derniers =
                flux.reverseRange(StreamContract.STREAM, touteLaPlage, Limit.limit().count(1));
        return derniers == null || derniers.isEmpty() ? null : derniers.get(0);
    }
}
