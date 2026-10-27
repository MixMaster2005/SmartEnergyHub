package com.smartenergy.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.service.CassandraMeasurementWriter;
import com.smartenergy.storage.stream.StorageStreamConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'ecriture du storage (etape 7) : CDC section 7.3, decisions D-08 a D-10, D-23.
 *
 * <p>Demarre Cassandra en Testcontainers et applique {@code scripts/init-cassandra.cql} (voir
 * {@link CassandraTestSupport}), puis verifie le comportement du writer sur les deux tables :
 * idempotence du rejeu, {@code USING TIMESTAMP} sur {@code latest} (une mesure en retard
 * n'ecrase pas une plus recente), omission des colonnes null et metriques.
 *
 * <p>Le consumer Redis est remplace par un mock : ce test ne touche ni au stream ni a Redis.
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
class CassandraMeasurementWriterTest {

    private static final Instant T1 = Instant.parse("2026-10-01T08:15:00Z");
    private static final Instant T1_ANCIENNE = Instant.parse("2026-10-01T08:14:00Z");

    @Container
    static final CassandraContainer<?> CASSANDRA = CassandraTestSupport.container();

    /** Meme session / meme template que CassandraMappingTest, pointes sur le conteneur. */
    @TestConfiguration
    static class CassandraTestConfig {

        @Bean(destroyMethod = "close")
        CqlSession cqlSession() {
            return CassandraTestSupport.connect(CASSANDRA);
        }

        @Bean
        CassandraTemplate cassandraTemplate(CqlSession session) {
            return new CassandraTemplate(session);
        }
    }

    /** Pas de consommation Redis dans ce test : seul le writer est sous test. */
    @MockitoBean
    StorageStreamConsumer storageConsumer;

    @BeforeAll
    static void initSchema() throws Exception {
        CassandraTestSupport.applySchema(CASSANDRA);
    }

    @Autowired
    CassandraMeasurementWriter writer;

    @Autowired
    CassandraTemplate cassandra;

    @Autowired
    CqlSession session;

    @Autowired
    MeterRegistry registry;

    private double errorsAvant;
    private long dureeAvant;

    @BeforeEach
    void repererMetriques() {
        errorsAvant = registry.find("energy_persist_errors_total").counter().count();
        dureeAvant = registry.find("energy_persist_duration").timer().count();
    }

    /** Rejeu du meme lot : une seule ligne par table (idempotence par cle primaire, D-08). */
    @Test
    void writeBatch_lotRejoue_ecraseLaMemeLigne() {
        EnergyMeasurement m = mesure("SENSOR-WRITER-IDEM", T1, 29.1);

        writer.writeBatch(List.of(m));
        writer.writeBatch(List.of(m));

        assertThat(compterParJour(m)).isEqualTo(1);
        assertThat(compterLatest(m.sensorId())).isEqualTo(1);
        assertThat(lireLatest(m.sensorId()).getTemperature()).isEqualTo(29.1);

        // Metriques du lot : un echantillon par passe, aucune erreur.
        assertThat(registry.find("energy_persist_duration").timer().count()).isEqualTo(dureeAvant + 2);
        assertThat(registry.find("energy_persist_errors_total").counter().count())
                .isEqualTo(errorsAvant);
    }

    /**
     * Decision D-09 : {@code latest} est ecrite avec {@code USING TIMESTAMP} = temps d'evenement,
     * donc une mesure en retard n'ecrase jamais une plus recente.
     */
    @Test
    void writeBatch_mesureEnRetard_nEcrasePasLatest() {
        EnergyMeasurement recente = mesure("SENSOR-WRITER-RETARD", T1, 29.5);
        EnergyMeasurement ancienne =
                mesure("SENSOR-WRITER-RETARD", T1_ANCIENNE, 21.0);

        writer.writeBatch(List.of(recente));
        writer.writeBatch(List.of(ancienne));

        assertThat(lireLatest(recente.sensorId()).getMeasuredAt()).isEqualTo(T1);
        assertThat(lireLatest(recente.sensorId()).getTemperature()).isEqualTo(29.5);

        // Les deux mesures restent dans l'historique (partition du jour).
        assertThat(compterParJour(recente)).isEqualTo(2);
    }

    /** Decision D-10 : les colonnes optionnelles absentes sont omises, jamais ecrites a null. */
    @Test
    void writeBatch_optionnelAbsent_laisseLaColonneVide() {
        EnergyMeasurement sansTemperature = mesure("SENSOR-WRITER-NULL", T1, null);

        writer.writeBatch(List.of(sansTemperature));

        Row row = lireLigneParJour(sansTemperature);
        assertThat(row.isNull("temperature")).isTrue();
        assertThat(row.getDouble("power_kw")).isEqualTo(3.72);
        assertThat(lireLatest(sansTemperature.sensorId()).getTemperature()).isNull();
    }

    /**
     * Decision D-23 : la premiere erreur du lot remonte (l'consumer n'acquittera pas) et la
     * metrique d'erreur est incrementee.
     */
    @Test
    void writeBatch_ecritureImpossible_propageLerreurEtCompte() {
        SimpleMeterRegistry metriquesLocales = new SimpleMeterRegistry();
        CqlSession ferme = CassandraTestSupport.connect(CASSANDRA);
        ferme.close();
        CassandraMeasurementWriter writerFerme =
                new CassandraMeasurementWriter(cassandra, ferme, metriquesLocales);

        assertThatThrownBy(() -> writerFerme.writeBatch(List.of(mesure("SENSOR-WRITER-KO", T1, 29.1))))
                .isInstanceOf(RuntimeException.class);

        assertThat(metriquesLocales.find("energy_persist_errors_total").counter().count())
                .isEqualTo(1);
        assertThat(metriquesLocales.find("energy_persist_duration").timer().count()).isEqualTo(1);
    }

    private static EnergyMeasurement mesure(String sensorId, Instant timestamp, Double temperature) {
        return new EnergyMeasurement(
                1, UUID.randomUUID(), "trace-test", sensorId, timestamp, timestamp,
                3.72, 226.4, temperature, 16.1, 50.0, SensorState.ON);
    }

    private long compterParJour(EnergyMeasurement m) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT count(*) FROM smartenergy.measurements_by_sensor_day "
                        + "WHERE sensor_id = ? AND day = ?",
                m.sensorId(), m.day()));
        return rs.one().getLong(0);
    }

    private long compterLatest(String sensorId) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT count(*) FROM smartenergy.latest_measurement_by_sensor WHERE sensor_id = ?",
                sensorId));
        return rs.one().getLong(0);
    }

    private Row lireLigneParJour(EnergyMeasurement m) {
        ResultSet rs = session.execute(SimpleStatement.newInstance(
                "SELECT * FROM smartenergy.measurements_by_sensor_day "
                        + "WHERE sensor_id = ? AND day = ? AND measured_at = ?",
                m.sensorId(), m.day(), m.timestamp()));
        return rs.one();
    }

    private LatestMeasurementEntity lireLatest(String sensorId) {
        LatestMeasurementEntity loaded = cassandra.selectOne(
                Query.query(Criteria.where("sensorId").is(sensorId)), LatestMeasurementEntity.class);
        assertThat(loaded).isNotNull();
        return loaded;
    }
}
