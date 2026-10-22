package com.smartenergy.storage.entity;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Entite Cassandra {@code latest_measurement_by_sensor} (CDC section 6.1, decision D-09).
 *
 * <p>Table a cle simple ({@code sensor_id}) : une ligne par capteur = derniere mesure connue.
 * Memes colonnes de valeur que la table principale.
 *
 * <p><b>Ecriture (etape 7)</b> : INSERT ... USING TIMESTAMP {@code measured_at} en microsecondes
 * (voir {@code CassandraMeasurementWriter}) pour que Cassandra resoudre les conflits sur le temps
 * d'EVENEMENT : une mesure en retard n'ecrase jamais une plus recente.
 *
 * <p>Meme regle de nulls que {@link MeasurementByDayEntity} (decision D-10) : les optionnels a
 * null sont omis de l'INSERT par Spring Data Cassandra.
 */
@Table("latest_measurement_by_sensor")
public class LatestMeasurementEntity {

    @PrimaryKey("sensor_id")
    private String sensorId;

    @Column("measured_at")
    private Instant measuredAt;

    @Column("power_kw")
    private Double powerKw;

    @Column("voltage")
    private Double voltage;

    @Column("temperature")
    private Double temperature;

    @Column("current_a")
    private Double currentA;

    @Column("frequency_hz")
    private Double frequencyHz;

    @Column("state")
    private String state;

    @Column("event_id")
    private UUID eventId;

    public LatestMeasurementEntity() {}

    /** Construit la ligne "derniere mesure" a partir de l'evenement canonique. */
    public static LatestMeasurementEntity from(EnergyMeasurement m) {
        LatestMeasurementEntity entity = new LatestMeasurementEntity();

        entity.sensorId = m.sensorId();
        entity.measuredAt = m.timestamp();
        entity.powerKw = m.powerKw();
        entity.voltage = m.voltage();
        entity.temperature = m.temperature();
        entity.currentA = m.currentA();
        entity.frequencyHz = m.frequencyHz();
        entity.state = m.state() != null ? m.state().name() : null;
        entity.eventId = m.eventId();

        return entity;
    }

    public String getSensorId() {
        return sensorId;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Double getPowerKw() {
        return powerKw;
    }

    public Double getVoltage() {
        return voltage;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getCurrentA() {
        return currentA;
    }

    public Double getFrequencyHz() {
        return frequencyHz;
    }

    public String getState() {
        return state;
    }

    public UUID getEventId() {
        return eventId;
    }
}
