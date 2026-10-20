package com.smartenergy.collector.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.contracts.measurement.MeasurementFields;
import com.smartenergy.platform.stream.StreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Tests unitaires (sans Spring ni Redis reel) de la publication des mesures dans le stream. */
class MeasurementPublisherTest {

    private static final String ID_REDIS = "1700000000000-0";
    private static final String NOM_STREAM = "energy-measurements";
    private static final long MAX_LEN = 500000L;
    private static final Instant TIMESTAMP = Instant.parse("2026-10-20T10:15:30.123Z");
    private static final Instant RECUE_LE = Instant.parse("2026-10-20T10:15:31.456Z");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MeasurementPublisher publisher =
            new MeasurementPublisher(
                    redis,
                    new StreamProperties(
                            NOM_STREAM,
                            "energy-measurements-dlq",
                            MAX_LEN,
                            "storage-group",
                            "collector-test",
                            50,
                            2000L,
                            Duration.ofSeconds(60),
                            Duration.ofSeconds(30),
                            5),
                    registry);

    @BeforeEach
    void brancher_redis() {
        when(redis.opsForStream()).thenReturn(streamOps);
    }

    private static EnergyMeasurement mesure() {
        EnergyMeasurementRequest requete =
                new EnergyMeasurementRequest("SENSOR-001", TIMESTAMP, 3.72, 226.4, null, null, null, null);
        return EnergyMeasurement.from(requete, "trace-test-01", RECUE_LE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> champs(Record<String, ?> record) {
        return (Map<String, String>) record.getValue();
    }

    @Test
    void mesure_valide_est_publiee_et_retourne_l_id_redis() {
        EnergyMeasurement measurement = mesure();
        when(streamOps.add(any(Record.class), any(XAddOptions.class))).thenReturn(RecordId.of(ID_REDIS));

        String id = publisher.publish(measurement);

        assertThat(id).isEqualTo(ID_REDIS);
        ArgumentCaptor<Record> recordCaptor = ArgumentCaptor.forClass(Record.class);
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOps, times(1)).add(recordCaptor.capture(), optionsCaptor.capture());

        Record<String, ?> record = recordCaptor.getValue();
        assertThat(record.getStream()).isEqualTo(NOM_STREAM);
        assertThat(champs(record)).containsExactlyInAnyOrderEntriesOf(MeasurementEventCodec.toFields(measurement));
        assertThat(optionsCaptor.getValue().getMaxlen()).isEqualTo(MAX_LEN);
        assertThat(optionsCaptor.getValue().isApproximateTrimming()).isTrue();
        assertThat(registry.get("energy_measurements_published_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("energy_measurements_publish").timer().count()).isEqualTo(1L);
    }

    @Test
    void redis_indisponible_leve_503_retry_after_5() {
        when(streamOps.add(any(Record.class), any(XAddOptions.class)))
                .thenThrow(new RedisConnectionFailureException("boom"));

        assertThatThrownBy(() -> publisher.publish(mesure()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.BROKER_UNAVAILABLE);
                    assertThat(e.retryAfterSeconds()).isEqualTo(5);
                });

        assertThat(registry.get("energy_measurements_published_total").counter().count()).isZero();
    }

    @Test
    void les_champs_utilisent_le_codec() {
        EnergyMeasurement measurement = mesure();
        when(streamOps.add(any(Record.class), any(XAddOptions.class))).thenReturn(RecordId.of(ID_REDIS));

        publisher.publish(measurement);

        ArgumentCaptor<Record> recordCaptor = ArgumentCaptor.forClass(Record.class);
        verify(streamOps).add(recordCaptor.capture(), any(XAddOptions.class));
        Map<String, String> fields = champs(recordCaptor.getValue());

        assertThat(fields).containsExactlyInAnyOrderEntriesOf(MeasurementEventCodec.toFields(measurement));
        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                MeasurementFields.SCHEMA_VERSION,
                MeasurementFields.EVENT_ID,
                MeasurementFields.TRACE_ID,
                MeasurementFields.SENSOR_ID,
                MeasurementFields.TIMESTAMP,
                MeasurementFields.RECEIVED_AT,
                MeasurementFields.POWER_KW,
                MeasurementFields.VOLTAGE);
        assertThat(fields).doesNotContainKeys(
                MeasurementFields.TEMPERATURE,
                MeasurementFields.CURRENT_A,
                MeasurementFields.FREQUENCY_HZ,
                MeasurementFields.STATE);
    }
}
