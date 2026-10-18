package com.smartenergy.contracts.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeasurementEventCodecTest {

    private static EnergyMeasurementRequest request(Double temperature, SensorState state) {
        return new EnergyMeasurementRequest("SENSOR-001", Instant.parse("2026-09-30T08:15:00Z"),
                3.72, 226.4, temperature, null, null, state);
    }

    @Test
    void aller_retour_avec_tous_les_champs() {
        EnergyMeasurementRequest r = new EnergyMeasurementRequest("SENSOR-001",
                Instant.parse("2026-09-30T08:15:00.123Z"), 3.72, 226.4, 29.1, 16.4, 50.0, SensorState.ON);
        EnergyMeasurement m = EnergyMeasurement.from(r, "trace-1", Instant.parse("2026-09-30T08:15:00.500Z"));

        Map<String, String> fields = MeasurementEventCodec.toFields(m);

        assertThat(fields).containsEntry("timestamp", "2026-09-30T08:15:00.123Z")
                .containsEntry("state", "ON").containsEntry("schemaVersion", "1");
        assertThat(MeasurementEventCodec.fromFields(fields)).isEqualTo(m);
    }

    @Test
    void champs_optionnels_absents_sont_omis_pas_vides() {
        EnergyMeasurement m = EnergyMeasurement.from(request(null, null), "t", Instant.parse("2026-09-30T08:15:01Z"));
        Map<String, String> fields = MeasurementEventCodec.toFields(m);

        assertThat(fields).doesNotContainKeys("temperature", "currentA", "frequencyHz", "state");
        assertThat(MeasurementEventCodec.fromFields(fields).temperature()).isNull();
    }

    @Test
    void jour_utc_est_derive_du_timestamp() {
        EnergyMeasurement m = EnergyMeasurement.from(request(null, null), "t", Instant.now());
        assertThat(m.day().toString()).isEqualTo("2026-09-30");
    }

    @Test
    void champ_obligatoire_manquant_est_rejete() {
        Map<String, String> f = new HashMap<>(MeasurementEventCodec.toFields(
                EnergyMeasurement.from(request(null, null), "t", Instant.parse("2026-09-30T08:15:01Z"))));
        f.remove("powerKw");
        assertThatThrownBy(() -> MeasurementEventCodec.fromFields(f))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("powerKw");
    }

    @Test
    void nombre_invalide_ou_non_fini_est_rejete() {
        Map<String, String> f = new HashMap<>(MeasurementEventCodec.toFields(
                EnergyMeasurement.from(request(null, null), "t", Instant.parse("2026-09-30T08:15:01Z"))));
        f.put("powerKw", "abc");
        assertThatThrownBy(() -> MeasurementEventCodec.fromFields(f)).isInstanceOf(MalformedEventException.class);
        f.put("powerKw", "NaN");
        assertThatThrownBy(() -> MeasurementEventCodec.fromFields(f)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void version_de_schema_non_supportee_est_rejetee() {
        Map<String, String> f = new HashMap<>(MeasurementEventCodec.toFields(
                EnergyMeasurement.from(request(null, null), "t", Instant.parse("2026-09-30T08:15:01Z"))));
        f.put("schemaVersion", "2");
        assertThatThrownBy(() -> MeasurementEventCodec.fromFields(f))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("schemaVersion");
    }

    @Test
    void champs_inconnus_sont_ignores() {
        Map<String, String> f = new HashMap<>(MeasurementEventCodec.toFields(
                EnergyMeasurement.from(request(null, null), "t", Instant.parse("2026-09-30T08:15:01Z"))));
        f.put("humidity", "55.0");
        assertThat(MeasurementEventCodec.fromFields(f).sensorId()).isEqualTo("SENSOR-001");
    }
}
