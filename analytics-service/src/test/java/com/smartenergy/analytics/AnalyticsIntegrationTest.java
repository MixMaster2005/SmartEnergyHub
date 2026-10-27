package com.smartenergy.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EventIds;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.MeasurementSchema;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.contracts.measurement.StreamContract;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration analytics : Redis + MongoDB en Testcontainers et un FAUX sensor-service
 * (serveur HTTP JDK embarque, CDC D-20). Scenarios 4, 8 et 11 du CDC.
 *
 * <p>Les evenements sont publies directement dans le stream ; on verifie les effets de bord via
 * l'API de consultation (summary, anomalies). Les index sont crees programmatiquement (equivalent
 * de {@code scripts/init-mongo.js} pour les collections utilisees).
 *
 * <p>Le scenario 11 arrete le faux sensor-service : les mesures doivent etre analysees avec le
 * seuil par defaut (thresholdSource = DEFAULT) — c'est le dernier test de la suite.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsIntegrationTest {

    private static final double SEUIL_PAR_DEFAUT_KW = 10.0;

    /** Faux sensor-service : 200 avec maxPowerKw = 10 pour les capteurs de la suite, 404 ailleurs. */
    private static final AtomicBoolean STUB_DEMARRE = new AtomicBoolean(false);
    private static HttpServer stub;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @BeforeAll
    static void demarreLeFauxSensorService() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/sensors/", exchange -> {
            String chemin = exchange.getRequestURI().getPath();
            String id = chemin.substring(chemin.lastIndexOf('/') + 1);
            byte[] reponse;
            if (id.startsWith("IT-") && !id.startsWith("IT-DEF")) {
                reponse = ("{\"sensorId\":\"" + id + "\",\"name\":\"capteur test\",\"maxPowerKw\":"
                        + SEUIL_PAR_DEFAUT_KW + ",\"active\":true,\"version\":1}").getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reponse.length);
            } else {
                reponse = "{\"status\":404,\"detail\":\"inconnu\"}".getBytes();
                exchange.sendResponseHeaders(404, reponse.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reponse);
            }
        });
        stub.start();
        STUB_DEMARRE.set(true);
    }

    @AfterAll
    static void arreteLeFauxSensorService() {
        if (STUB_DEMARRE.compareAndSet(true, false)) {
            stub.stop(0);
        }
    }

    @DynamicPropertySource
    static void proprieteSpring(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/analytics_db");
        registry.add("smartenergy.analytics.sensor-service-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MongoTemplate mongo;

    /** Index (equivalent init-mongo.js) indispensable a l'idempotence des anomalies. */
    @BeforeEach
    void indexMongo() {
        mongo.indexOps("anomalies").ensureIndex(
                new Index().on("eventId", Direction.ASC).on("type", Direction.ASC).unique());
        mongo.indexOps("daily_aggregates").ensureIndex(
                new Index().on("sensorId", Direction.ASC).on("date", Direction.ASC).unique());
    }

    // ------------------------------------------------------ agregats (etape 9)
    @Test
    @Order(1)
    void mesuresValides_agregatsHourlyCorrects() throws Exception {
        publier(mesure("IT-AGG-1", Instant.now().minusSeconds(90), 2.0));
        publier(mesure("IT-AGG-1", Instant.now().minusSeconds(60), 3.0));
        publier(mesure("IT-AGG-1", Instant.now().minusSeconds(30), 4.0));

        attendre(60, "3 mesures agrégées pour IT-AGG-1",
                () -> summaryCompporte("IT-AGG-1", "\"count\":3"));

        String summary = summary("IT-AGG-1");
        assertThat(summary).contains("\"avgPowerKw\":3.0")
                .contains("\"minPowerKw\":2.0")
                .contains("\"maxPowerKw\":4.0")
                .contains("\"thresholdKw\":" + SEUIL_PAR_DEFAUT_KW); // seuil lu chez le faux sensor-service
    }

    // ------------------------------------------------------ scenario 4
    @Test
    @Order(2)
    void picX3_anomalieThresholdListee() throws Exception {
        publier(mesure("IT-ANOM-1", Instant.now().minusSeconds(45), 30.0));

        attendre(60, "anomalie THRESHOLD pour IT-ANOM-1",
                () -> anomalies("IT-ANOM-1").contains("\"totalElements\":1"));

        String json = anomalies("IT-ANOM-1");
        assertThat(json).contains("\"type\":\"THRESHOLD\"")
                .contains("\"severity\":\"HIGH\"")
                .contains("\"powerKw\":30.0")
                .contains("\"thresholdKw\":" + SEUIL_PAR_DEFAUT_KW)
                .contains("\"thresholdSource\":\"SENSOR\"");
    }

    // ------------------------------------------------------ scenario 8
    @Test
    @Order(3)
    void doublon_compteursInchanges() throws Exception {
        EnergyMeasurement m = mesure("IT-DUP-2", Instant.now().minusSeconds(20), 5.0);
        publier(m);
        attendre(60, "la premiere mesure est comptee", () -> summaryCompporte("IT-DUP-2", "\"count\":1"));

        publier(m); // meme eventId : dedup (Redis) + degeneration intra-lot
        attendre(60, "le rejeu est consomme et acquitte", () -> groupeVide());

        assertThat(summary("IT-DUP-2")).contains("\"count\":1");
    }

    // ------------------------------------------------------ scenario 11
    @Test
    @Order(4)
    void sensorServiceArrete_seuilParDefaut() throws Exception {
        // coupure totale du sensor-service : la recuperation Resilience4j renvoie le seuil par defaut
        arreteLeFauxSensorService();

        publier(mesure("IT-DEF-1", Instant.now().minusSeconds(15), 30.0));
        attendre(90, "anomalie THRESHOLD avec seuil par defaut",
                () -> anomalies("IT-DEF-1").contains("\"totalElements\":1"));

        String json = anomalies("IT-DEF-1");
        assertThat(json).contains("\"thresholdKw\":" + SEUIL_PAR_DEFAUT_KW)
                .contains("\"thresholdSource\":\"DEFAULT\"");
    }

    // --------------------------------------------------------------- helpers
    private static EnergyMeasurement mesure(String sensorId, Instant timestamp, double powerKw) {
        Instant ts = timestamp.truncatedTo(ChronoUnit.MILLIS);
        return new EnergyMeasurement(
                MeasurementSchema.CURRENT_VERSION,
                EventIds.forMeasurement(sensorId, ts),
                "trace-integration-test",
                sensorId,
                ts,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                powerKw,
                226.4,
                null,
                null,
                null,
                SensorState.ON);
    }

    private void publier(EnergyMeasurement m) {
        redis.opsForStream().add(StreamRecords
                .string(MeasurementEventCodec.toFields(m))
                .withStreamKey(StreamContract.STREAM));
    }

    private String summary(String sensorId) {
        return rest.getForObject("/api/analytics/{id}/summary", String.class, sensorId);
    }

    private boolean summaryCompporte(String sensorId, String fragment) {
        String json = summary(sensorId);
        return json != null && json.contains(fragment);
    }

    private String anomalies(String sensorId) {
        return rest.getForObject("/api/analytics/{id}/anomalies?type=THRESHOLD&size=100",
                String.class, sensorId);
    }

    /** Groupe analytics entierement vide (pending = 0) : le lot a ete acquitte. */
    private boolean groupeVide() {
        var resume = redis.opsForStream().pending(StreamContract.STREAM, StreamContract.ANALYTICS_GROUP);
        return resume == null || resume.getTotalPendingMessages() == 0;
    }

    private static void attendre(int secondes, String libelle,
            java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(secondes);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        assertThat(condition.getAsBoolean()).as("attente echouee : %s (%ds)", libelle, secondes).isTrue();
    }
}
