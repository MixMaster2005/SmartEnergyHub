package com.smartenergy.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EventIds;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.MeasurementSchema;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.contracts.measurement.StreamContract;
import com.smartenergy.storage.stream.StorageStreamConsumer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration du storage : Cassandra + Redis en Testcontainers, application complete
 * (CDC section 12, scenarios 3, 5, 6, 8). Le schema vient de {@code scripts/init-cassandra.cql}
 * (decision D-11), applique avant le chargement du contexte.
 *
 * <p>Les evenements sont publies DIRECTEMENT dans le stream (pas via le collector) : on teste ici
 * la boucle consumer -> writer -> API de lecture. Le dernier test arrete le consumer, publie des
 * mesures (aucune ne doit parvenir en base) puis le relance : aucune mesure perdue (scenarios 5
 * et 6).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageIntegrationTest {

    private static final AtomicBoolean SCHEMA_APPLIQUE = new AtomicBoolean(false);

    @Container
    static final CassandraContainer<?> CASSANDRA = CassandraTestSupport.container();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    /** Schema avant le chargement du contexte Spring (l'extension Testcontainers demarre avant). */
    @BeforeAll
    static void appliqueLeSchema() throws Exception {
        CassandraTestSupport.applySchema(CASSANDRA);
        SCHEMA_APPLIQUE.set(true);
    }

    @DynamicPropertySource
    static void proprieteSpring(DynamicPropertyRegistry registry) {
        assertThat(SCHEMA_APPLIQUE.get())
                .as("le schema doit etre applique avant la creation du contexte")
                .isTrue();
        registry.add("spring.cassandra.contact-points", CASSANDRA::getHost);
        registry.add("spring.cassandra.port", () -> CASSANDRA.getFirstMappedPort());
        registry.add("spring.cassandra.local-datacenter", () -> "datacenter1");
        registry.add("spring.cassandra.keyspace-name", () -> "smartenergy");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private com.datastax.oss.driver.api.core.CqlSession session;

    @Autowired
    private StorageStreamConsumer consumer;

    @Autowired
    private ObjectMapper mapper;

    // ------------------------------------------------------------- scenario 3
    @Test
    @Order(1)
    void evenementPublie_estRetrouveeParApi() throws Exception {
        EnergyMeasurement m = mesure("IT-API-1", Instant.now().minusSeconds(30), 5.5);
        publier(m);

        attendre(60, "la mesure IT-API-1 est lisible via /latest",
                () -> dernierStatut("IT-API-1") == 200);

        String latest = rest.getForObject("/api/measurements/IT-API-1/latest", String.class);
        assertThat(latest).contains("\"powerKw\":5.5").contains("\"voltage\":226.4");

        // historique : la mesure publiee y figure (etape 8)
        String historique = rest.getForObject("/api/measurements/IT-API-1?limit=10", String.class);
        assertThat(nbItems(historique)).isEqualTo(1);
    }

    // ------------------------------------------------------------- scenario 8
    @Test
    @Order(2)
    void doublon_neCreeQuUneSeuleLigne() throws Exception {
        EnergyMeasurement m = mesure("IT-DUP-1", Instant.now().minusSeconds(25), 4.4);
        publier(m);
        attendre(60, "la premiere publication est persistee",
                () -> compte("IT-DUP-1", m.day()) == 1);

        // rejeu exact (meme eventId) : le writer est idempotent (USING TIMESTAMP, D-09/D-10)
        publier(m);
        attendre(60, "le rejeu est consomme et acquitte", () -> groupeVide());

        assertThat(compte("IT-DUP-1", m.day())).isEqualTo(1);
    }

    // -------------------------------------------------------------- DLQ
    @Test
    @Order(3)
    void evenementIllisible_vaEnDlq_etLeFluxContinue() throws Exception {
        long dlqAvant = xlen(StreamContract.DLQ_STREAM);

        // structure illisible : champs obligatoires absents -> MalformedEventException
        redis.opsForStream().add(StreamRecords
                .string(java.util.Map.of("sensorId", "IT-DLQ-1", "state", "X"))
                .withStreamKey(StreamContract.STREAM));
        attendre(30, "l'evenement illisible est en DLQ",
                () -> xlen(StreamContract.DLQ_STREAM) > dlqAvant);

        // le flux continue : la mesure suivante est bien traitee
        publier(mesure("IT-DLQ-2", Instant.now().minusSeconds(10), 6.6));
        attendre(60, "la mesure suivante est persistee malgre la DLQ",
                () -> dernierStatut("IT-DLQ-2") == 200);
    }

    @Test
    @Order(4)
    void mesurePlusAncienne_neChangePasLatest() throws Exception {
        EnergyMeasurement recente = mesure("IT-OLD-1", Instant.now().minusSeconds(60), 7.7);
        publier(recente);
        attendre(60, "la mesure recente est lisible", () -> dernierStatut("IT-OLD-1") == 200
                && dernierCorps("IT-OLD-1").contains("\"powerKw\":7.7"));

        // une mesure plus ancienne arrive apres : USING TIMESTAMP empeche d'ecraser le latest (D-10)
        publier(mesure("IT-OLD-1", Instant.now().minus(3, ChronoUnit.HOURS), 9.9));
        attendre(60, "l'evenement ancien est consomme", () -> compte("IT-OLD-1", recente.day()) == 2);

        assertThat(dernierCorps("IT-OLD-1")).contains("\"powerKw\":7.7");
    }

    // -------------------------------------------------------- scenarios 5 et 6
    @Test
    @Order(5)
    void arretDuConsumer_puisReprise_aucuneMesurePerdue() throws Exception {
        consumer.stop();
        try {
            for (int i = 1; i <= 5; i++) {
                publier(mesure("IT-STP-1", Instant.now().minusSeconds(400 + i * 10L), 3.0 + i));
            }
            Thread.sleep(2000); // laisse le temps au consumer (arrete) de ne rien consommer
            assertThat(compte("IT-STP-1", LocalDate.now(java.time.ZoneOffset.UTC)))
                    .as("aucune mesure ne peut etre ecrite tant que le consumer est arrete")
                    .isZero();
        } finally {
            consumer.start(); // reprise : XREADGROUP > livre les messages non acquittes
        }

        attendre(90, "les 5 mesures publiees pendant l'arret sont toutes en base",
                () -> compte("IT-STP-1", LocalDate.now(java.time.ZoneOffset.UTC)) == 5);
        assertThat(compte("IT-STP-1", LocalDate.now(java.time.ZoneOffset.UTC))).isEqualTo(5);
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

    private RecordId publier(EnergyMeasurement m) {
        return redis.opsForStream().add(StreamRecords
                .string(MeasurementEventCodec.toFields(m))
                .withStreamKey(StreamContract.STREAM));
    }

    private int dernierStatut(String sensorId) {
        ResponseEntity<String> r =
                rest.getForEntity("/api/measurements/{id}/latest", String.class, sensorId);
        return r.getStatusCode().value();
    }

    private String dernierCorps(String sensorId) {
        return rest.getForObject("/api/measurements/{id}/latest", String.class, sensorId);
    }

    /** Comptage CQL direct (requetee par la cle de partition (sensor_id, day)). */
    private long compte(String sensorId, LocalDate day) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT count(*) FROM smartenergy.measurements_by_sensor_day "
                        + "WHERE sensor_id=? AND day=?",
                sensorId, day));
        return rs.one().getLong(0);
    }

    /** Groupe storage entirely vide (pending = 0 et lag = 0) : tous les lots ont ete acquittes. */
    private boolean groupeVide() {
        var resume = redis.opsForStream().pending(StreamContract.STREAM, StreamContract.STORAGE_GROUP);
        return resume == null || resume.getTotalPendingMessages() == 0;
    }

    private long xlen(String stream) {
        Long size = redis.opsForStream().size(stream);
        return size == null ? 0 : size;
    }

    private int nbItems(String historiqueJson) throws Exception {
        JsonNode items = mapper.readTree(historiqueJson).path("items");
        return items.isArray() ? items.size() : -1;
    }

    private static void attendre(int secondes, String libelle, java.util.function.BooleanSupplier c)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(secondes);
        while (Instant.now().isBefore(deadline)) {
            if (c.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        assertThat(c.getAsBoolean()).as("attente echouee : %s (%ds)", libelle, secondes).isTrue();
    }
}
