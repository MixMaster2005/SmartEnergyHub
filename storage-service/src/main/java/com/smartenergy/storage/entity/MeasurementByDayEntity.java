package com.smartenergy.storage.entity;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.util.UUID;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Entite Cassandra {@code measurements_by_sensor_day} (CDC section 6.1, decision D-08).
 *
 * <p>Mapping colonnes : {@code power_kw, voltage, temperature, current_a, frequency_hz, state,
 * event_id} (+ cle composite {@link MeasurementKey}). Types optionnels en objets ({@code Double},
 * {@code String}, {@code UUID}) pour pouvoir etre absents.
 *
 * <p><b>Tombstones (decision D-10)</b> : ne jamais ecrire un null EXPLICITE. Spring Data Cassandra
 * (4.5) omet par defaut les proprietes null de l'INSERT ({@code InsertOptions.insertNulls} =
 * false) : aucun reglage supplementaire n'est necessaire, {@link #from(EnergyMeasurement)} laisse
 * les optionnels a null et les colonnes sont simplement liees.
 */
@Table("measurements_by_sensor_day")
public class MeasurementByDayEntity {

    @PrimaryKey
    private MeasurementKey key;

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

    public MeasurementByDayEntity() {}

    /** Construit l'entite a partir de l'evenement canonique (cle = sensorId + jour UTC + timestamp). */
    public static MeasurementByDayEntity from(EnergyMeasurement m) {
        MeasurementByDayEntity entity = new MeasurementByDayEntity();

        entity.key = new MeasurementKey(m.sensorId(), m.day(), m.timestamp());

        entity.powerKw = m.powerKw();
        entity.voltage = m.voltage();
        entity.temperature = m.temperature();
        entity.currentA = m.currentA();
        entity.frequencyHz = m.frequencyHz();
        entity.state = m.state() != null ? m.state().name() : null;
        entity.eventId = m.eventId();

        return entity;
    }

    public MeasurementKey getKey() {
        return key;
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
