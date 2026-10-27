package com.smartenergy.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.entity.MeasurementByDayEntity;
import com.smartenergy.storage.entity.MeasurementKey;
import com.smartenergy.storage.stream.StorageStreamConsumer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.StatementFactory;
import org.springframework.data.cassandra.core.convert.MappingCassandraConverter;
import org.springframework.data.cassandra.core.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration du mapping Cassandra etape 5 (CDC section 6.1, decisions D-08 a D-11).
 *
 * <p>Demarre Cassandra en Testcontainers, applique {@code scripts/init-cassandra.cql} (schema
 * fourni par le script, jamais par Spring : D-11), puis verifie le mapping des deux entites.
 * Chaque test utilise un capteur dedie pour rester independant des autres.
 *
 * <p>L'autoconfiguration Cassandra de Spring Boot est exclue : le conteneur expose un port
 * aleatoire et la session est fournie ici, directement pointee sur le conteneur.
 */
@Testcontainers
@SpringBootTest(
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.cassandra"
                        + ".CassandraHealthContributorAutoConfiguration",
                "management.endpoint.health.group.readiness.include=readinessState"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CassandraMappingTest {

    private static final Instant T1 = Instant.parse("2026-09-30T08:15:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Container
    static final CassandraContainer<?> CASSANDRA = CassandraTestSupport.container();

    /** Session et template pointes sur le conteneur demarre par Testcontainers. */
    @TestConfiguration
    static class CassandraTestConfig {

        @Bean(destroyMethod = "close")
        CqlSession cqlSession() {
            return CqlSession.builder()
                    .addContactPoint(new InetSocketAddress(
                            CASSANDRA.getHost(), CASSANDRA.getFirstMappedPort()))
                    .withLocalDatacenter("datacenter1")
                    .withKeyspace("smartenergy")
                    .build();
        }

        @Bean
        CassandraTemplate cassandraTemplate(CqlSession session) {
            return new CassandraTemplate(session);
        }
    }

    @BeforeAll
    static void initSchema() throws Exception {
        CassandraTestSupport.applySchema(CASSANDRA);
    }

    private static EnergyMeasurement measurement(String sensorId, Instant timestamp, Double temperature) {
        return new EnergyMeasurement(
                1, UUID.randomUUID(), "trace-test", sensorId, timestamp, timestamp,
                3.72, 226.4, temperature, 16.1, 50.0, SensorState.ON);
    }

    @Autowired
    CassandraTemplate cassandra;

    @Autowired
    CqlSession session;

    /** Le mapping seul ne consomme pas Redis : pas de consumer reel dans ce test. */
    @MockitoBean
    StorageStreamConsumer storageConsumer;

    @Test
    void measurementByDay_roundTrip_conserveTypesEtValeurs() {
        String sensorId = "SENSOR-RT";
        EnergyMeasurement m = measurement(sensorId, T1, 29.1);
        MeasurementByDayEntity entity = MeasurementByDayEntity.from(m);

        cassandra.insert(entity);
        MeasurementByDayEntity loaded =
                cassandra.selectOneById(new MeasurementKey(sensorId, DAY, T1), MeasurementByDayEntity.class);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getKey().getSensorId()).isEqualTo(sensorId);
        assertThat(loaded.getKey().getDay()).isEqualTo(DAY);
        assertThat(loaded.getKey().getMeasuredAt()).isEqualTo(T1);
        assertThat(loaded.getPowerKw()).isEqualTo(3.72);
        assertThat(loaded.getVoltage()).isEqualTo(226.4);
        assertThat(loaded.getTemperature()).isEqualTo(29.1);
        assertThat(loaded.getCurrentA()).isEqualTo(16.1);
        assertThat(loaded.getFrequencyHz()).isEqualTo(50.0);
        assertThat(loaded.getState()).isEqualTo("ON");
        assertThat(loaded.getEventId()).isEqualTo(m.eventId());
    }

    @Test
    void measurementByDay_insertionIdempotente_uneSeuleLigne() {
        String sensorId = "SENSOR-IDEM";
        EnergyMeasurement m = measurement(sensorId, T1, 29.1);

        cassandra.insert(MeasurementByDayEntity.from(m));
        cassandra.insert(MeasurementByDayEntity.from(m));

        assertThat(countByDay(sensorId)).isEqualTo(1);
    }

    @Test
    void measurementByDay_clusteringDescPlusRecentDabord() {
        String sensorId = "SENSOR-DESC";
        cassandra.insert(MeasurementByDayEntity.from(
                measurement(sensorId, Instant.parse("2026-09-30T08:15:00Z"), 29.0)));
        cassandra.insert(MeasurementByDayEntity.from(
                measurement(sensorId, Instant.parse("2026-09-30T08:15:04Z"), 29.2)));
        cassandra.insert(MeasurementByDayEntity.from(
                measurement(sensorId, Instant.parse("2026-09-30T08:15:02Z"), 29.1)));

        assertThat(selectMeasuredAts(sensorId)).containsExactly(
                Instant.parse("2026-09-30T08:15:04Z"),
                Instant.parse("2026-09-30T08:15:02Z"),
                Instant.parse("2026-09-30T08:15:00Z"));
    }

    @Test
    void latest_upsertRemplaceLaLigneDuCapteur() {
        String sensorId = "SENSOR-LATEST";
        cassandra.insert(LatestMeasurementEntity.from(measurement(sensorId, T1, 29.0)));

        Instant newer = T1.plusSeconds(2);
        UUID newerEventId = UUID.randomUUID();
        cassandra.insert(LatestMeasurementEntity.from(
                new EnergyMeasurement(
                        1, newerEventId, "trace-test", sensorId, newer, newer,
                        3.80, 227.0, 29.5, 16.0, 50.0, SensorState.ON)));

        LatestMeasurementEntity loaded = cassandra.selectOne(
                Query.query(Criteria.where("sensorId").is(sensorId)), LatestMeasurementEntity.class);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getSensorId()).isEqualTo(sensorId);
        assertThat(loaded.getMeasuredAt()).isEqualTo(newer);
        assertThat(loaded.getTemperature()).isEqualTo(29.5);
        assertThat(loaded.getState()).isEqualTo("ON");
        assertThat(loaded.getEventId()).isEqualTo(newerEventId);
        assertThat(countLatest(sensorId)).isEqualTo(1);
    }

    /**
     * Decision D-10 : les proprietes null sont OBMIES de l'INSERT (InsertOptions.insertNulls=false
     * par defaut dans Spring Data Cassandra 4.5) — pas de null explicite, pas de tombstone.
     */
    @Test
    void insertGenere_ometLesColonnesNulls() {
        MappingCassandraConverter converter = new MappingCassandraConverter();
        CassandraPersistentEntity<?> persistentEntity =
                converter.getMappingContext().getRequiredPersistentEntity(MeasurementByDayEntity.class);
        StatementFactory factory = new StatementFactory(converter);

        // temperature null : la colonne ne doit pas apparaitre dans la requete generee.
        MeasurementByDayEntity withNull =
                MeasurementByDayEntity.from(measurement("SENSOR-NULL", T1, null));
        SimpleStatement withoutNull = factory
                .insert(withNull, InsertOptions.empty(), persistentEntity,
                        CqlIdentifier.fromCql("measurements_by_sensor_day"))
                .build();

        assertThat(withNull.getTemperature()).isNull();
        assertThat(withoutNull.getQuery())
                .contains("power_kw")
                .contains("state")
                .doesNotContain("temperature");

        // Non-null : la colonne est bien liee.
        MeasurementByDayEntity withValue =
                MeasurementByDayEntity.from(measurement("SENSOR-NOTNULL", T1, 29.1));
        SimpleStatement withTemperature = factory
                .insert(withValue, InsertOptions.empty(), persistentEntity,
                        CqlIdentifier.fromCql("measurements_by_sensor_day"))
                .build();

        assertThat(withTemperature.getQuery()).contains("temperature");
    }

    private long countByDay(String sensorId) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT count(*) FROM smartenergy.measurements_by_sensor_day "
                        + "WHERE sensor_id = ? AND day = ?",
                sensorId, DAY));
        return rs.one().getLong(0);
    }

    private long countLatest(String sensorId) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT count(*) FROM smartenergy.latest_measurement_by_sensor WHERE sensor_id = ?",
                sensorId));
        return rs.one().getLong(0);
    }

    private List<Instant> selectMeasuredAts(String sensorId) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT measured_at FROM smartenergy.measurements_by_sensor_day "
                        + "WHERE sensor_id = ? AND day = ?",
                sensorId, DAY));
        List<Instant> result = new ArrayList<>();
        for (Row row : rs) {
            result.add(row.getInstant("measured_at"));
        }
        return result;
    }
}
