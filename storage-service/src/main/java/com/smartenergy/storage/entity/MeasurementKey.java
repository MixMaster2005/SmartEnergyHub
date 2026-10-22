package com.smartenergy.storage.entity;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

/**
 * Cle primaire composite de {@code measurements_by_sensor_day} (CDC section 6.1, decision D-08).
 *
 * <p>PRIMARY KEY {@code ((sensor_id, day), measured_at))} :
 * <ul>
 *   <li>{@code sensor_id} et {@code day} = cle de PARTITION (ordinal 0 et 1) ;</li>
 *   <li>{@code measured_at} = cle de CLUSTERING (ordinal 2, DESCENDING).</li>
 * </ul>
 *
 * <p>La partition est {@code (sensor_id, day)} et pas {@code sensor_id} seul pour garder des
 * partitions bornees : 1 mesure / 2 s => ~43 200 lignes par jour et par capteur. Avec
 * {@code sensor_id} seul, une relecture de plusieurs jours ferait un scan de partition geant.
 * {@code day} est la date UTC de {@code measured_at} (voir {@code EnergyMeasurement#day()}).
 */
@PrimaryKeyClass
public class MeasurementKey implements Serializable {

    @PrimaryKeyColumn(name = "sensor_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String sensorId;

    @PrimaryKeyColumn(name = "day", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private LocalDate day;

    @PrimaryKeyColumn(
            name = "measured_at",
            ordinal = 2,
            type = PrimaryKeyType.CLUSTERED,
            ordering = Ordering.DESCENDING)
    private Instant measuredAt;

    public MeasurementKey() {}

    public MeasurementKey(String sensorId, LocalDate day, Instant measuredAt) {
        this.sensorId = sensorId;
        this.day = day;
        this.measuredAt = measuredAt;
    }

    public String getSensorId() {
        return this.sensorId;
    }

    public LocalDate getDay() {
        return this.day;
    }

    public Instant getMeasuredAt() {
        return this.measuredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeasurementKey)) return false;
        MeasurementKey that = (MeasurementKey) o;
        return Objects.equals(sensorId, that.getSensorId())
                && Objects.equals(day, that.getDay())
                && Objects.equals(measuredAt, that.getMeasuredAt());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sensorId, day, measuredAt);
    }
}
