package com.smartenergy.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.sensor.SensorRequest;
import com.smartenergy.contracts.sensor.SensorResponse;
import com.smartenergy.sensor.model.SensorDocument;
import com.smartenergy.sensor.repository.SensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration du sensor : MongoDB en Testcontainers, application complete
 * (@SpringBootTest, port aleatoire). CDC section 6, critere de l'etape 6 (metadonnees
 * consultables avec Cassandra arrete).
 *
 * <p>La suite ne demarre AUCUN conteneur Cassandra et sensor-service n'a meme pas le driver
 * CqlSession au classpath : la lisibilite des metadonnees ne peut donc pas dependre de Cassandra.
 * L'unicite de {@code sensorId} repose sur l'index {@code uk_sensorId}, cree programmatiquement
 * ici (equivalent de {@code scripts/init-mongo.js} : Spring ne cree pas les index lui-meme).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensorServiceIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        // sans authentification : le conteneur est neuf, l'utilisateur sensor_app n'existe pas
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/sensor_db");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private SensorRepository repository;

    /** Index unique de prod (scripts/init-mongo.js), idempotent a chaque test. */
    @BeforeEach
    void indexUniqueSensorId() {
        mongo.indexOps("sensors")
                .ensureIndex(new Index()
                        .on("sensorId", Sort.Direction.ASC)
                        .unique()
                        .named("uk_sensorId"));
    }

    // --------------------------------------------------------------- creation puis lecture
    @Test
    @Order(1)
    void creation_puis_lecture_donne_le_memes_capteur() {
        ResponseEntity<SensorResponse> cree =
                rest.exchange("/api/sensors", HttpMethod.POST, entity(json("IT-CR-1", "Batiment IT")),
                        SensorResponse.class);

        assertThat(cree.getStatusCode().value()).isEqualTo(201);
        assertThat(cree.getHeaders().getLocation()).asString().endsWith("/api/sensors/IT-CR-1");
        assertThat(cree.getBody()).isNotNull();
        assertThat(cree.getBody().sensorId()).isEqualTo("IT-CR-1");
        assertThat(cree.getBody().active()).isTrue(); // actif par defaut
        assertThat(cree.getBody().maxPowerKw()).isEqualTo(8.5);

        // lecture directe
        ResponseEntity<SensorResponse> lu =
                rest.getForEntity("/api/sensors/IT-CR-1", SensorResponse.class);
        assertThat(lu.getStatusCode().value()).isEqualTo(200);
        assertThat(lu.getBody()).isNotNull();
        assertThat(lu.getBody().name()).isEqualTo("Compteur IT");
        assertThat(lu.getBody().building()).isEqualTo("Batiment IT");
        assertThat(lu.getBody().tags()).containsExactly("it");
        assertThat(lu.getBody().version()).isGreaterThanOrEqualTo(0L);

        // lecture filtree (building) dans la page
        ResponseEntity<PageResponse<SensorResponse>> page = rest.exchange(
                "/api/sensors?building=Batiment IT", HttpMethod.GET, null,
                new ParameterizedTypeReference<PageResponse<SensorResponse>>() {});
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).isNotNull();
        assertThat(page.getBody().items()).extracting(SensorResponse::sensorId).contains("IT-CR-1");
    }

    // --------------------------------------------------------------- etape 6 : sans Cassandra
    @Test
    @Order(2)
    void metadonnees_consultables_sans_cassandra() {
        // aucun conteneur Cassandra dans cette suite et aucun driver Cassandra au classpath :
        // la lecture des metadonnees ne peut reposer que sur MongoDB
        assertThat(ClassUtils.isPresent("com.datastax.oss.driver.api.core.cql.CqlSession",
                getClass().getClassLoader())).isFalse();
        assertThat(MONGO.isRunning()).isTrue();

        ResponseEntity<SensorResponse> cree =
                rest.exchange("/api/sensors", HttpMethod.POST, entity(json("IT-SANS-CASS", "Batiment MC")),
                        SensorResponse.class);
        assertThat(cree.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<SensorResponse> lu =
                rest.getForEntity("/api/sensors/IT-SANS-CASS", SensorResponse.class);
        assertThat(lu.getStatusCode().value()).isEqualTo(200);
        assertThat(lu.getBody()).isNotNull();
        assertThat(lu.getBody().building()).isEqualTo("Batiment MC");
    }

    // --------------------------------------------------------------- doublon -> 409
    @Test
    @Order(3)
    void doublon_via_index_unique_donne_409_et_le_index_rejette_l_insertion_brute() {
        ResponseEntity<SensorResponse> premier =
                rest.exchange("/api/sensors", HttpMethod.POST, entity(json("IT-DUP-1", "Batiment D")),
                        SensorResponse.class);
        assertThat(premier.getStatusCode().value()).isEqualTo(201);

        // second POST du meme identifiant -> 409 problem+json
        ResponseEntity<String> second =
                rest.exchange("/api/sensors", HttpMethod.POST, entity(json("IT-DUP-1", "Batiment D")),
                        String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(second.getBody()).contains("\"code\":\"SENSOR_ALREADY_EXISTS\"");

        // le filet de sécurité (course entre deux creations) : l'index unique existe bien
        // et une insertion brute en double est refusee par MongoDB lui-meme
        assertThat(mongo.indexOps("sensors").getIndexInfo())
                .extracting(IndexInfo::getName)
                .contains("uk_sensorId");
        SensorDocument brut = new SensorDocument("IT-DUP-1");
        brut.setName("Course");
        assertThatThrownBy(() -> repository.save(brut)).isInstanceOf(DuplicateKeyException.class);
    }

    // --------------------------------------------------------------- helpers
    private static HttpEntity<String> entity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private static String json(String sensorId, String building) {
        return "{\"sensorId\":\"" + sensorId + "\",\"name\":\"Compteur IT\",\"building\":\"" + building
                + "\",\"floor\":1,\"maxPowerKw\":8.5,\"tags\":[\"it\"]}";
    }
}
