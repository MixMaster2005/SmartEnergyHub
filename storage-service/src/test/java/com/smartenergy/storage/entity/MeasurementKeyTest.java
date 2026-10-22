package com.smartenergy.storage.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Tests unitaires de la cle primaire composite (etape 5, decision D-08). */
class MeasurementKeyTest {

    private static final Instant T = Instant.parse("2026-09-30T08:15:00Z");

    @Test
    void equals_retourneVraiPourClesIdentiques() {
        MeasurementKey a = new MeasurementKey("SENSOR-001", LocalDate.of(2026, 9, 30), T);
        MeasurementKey b = new MeasurementKey("SENSOR-001", LocalDate.of(2026, 9, 30), T);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void equals_retourneFauxSiUnChampDiffere() {
        MeasurementKey base = new MeasurementKey("SENSOR-001", LocalDate.of(2026, 9, 30), T);

        assertThat(base).isNotEqualTo(new MeasurementKey("SENSOR-002", LocalDate.of(2026, 9, 30), T));
        assertThat(base).isNotEqualTo(new MeasurementKey("SENSOR-001", LocalDate.of(2026, 10, 1), T));
        assertThat(base).isNotEqualTo(new MeasurementKey("SENSOR-001", LocalDate.of(2026, 9, 30), T.plusSeconds(1)));
    }

    @Test
    void getters_retournentLesValeurs() {
        LocalDate day = LocalDate.of(2026, 9, 30);
        MeasurementKey key = new MeasurementKey("SENSOR-001", day, T);

        assertThat(key.getSensorId()).isEqualTo("SENSOR-001");
        assertThat(key.getDay()).isEqualTo(day);
        assertThat(key.getMeasuredAt()).isEqualTo(T);
    }
}
