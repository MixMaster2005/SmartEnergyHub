package com.smartenergy.contracts.measurement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * EVENEMENT canonique publie dans le stream Redis (CDC section 5.3) et consomme par
 * storage-service et analytics-service. Sa representation Redis (champs texte) est definie par
 * {@link MeasurementEventCodec}.
 *
 * <ul>
 *   <li>{@code timestamp} = temps d'evenement (heure de mesure, UTC, tronque a la milliseconde)</li>
 *   <li>{@code receivedAt} = heure de reception par le collector</li>
 *   <li>champs optionnels : {@code null} si absents</li>
 * </ul>
 */
public record EnergyMeasurement(
        int schemaVersion,
        UUID eventId,
        String traceId,
        String sensorId,
        Instant timestamp,
        Instant receivedAt,
        double powerKw,
        double voltage,
        Double temperature,
        Double currentA,
        Double frequencyHz,
        SensorState state) {

    public EnergyMeasurement {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** Construit l'evenement a partir de la requete HTTP validee (calcule l'eventId deterministe). */
    public static EnergyMeasurement from(EnergyMeasurementRequest r, String traceId, Instant receivedAt) {
        Instant ts = r.timestamp().truncatedTo(ChronoUnit.MILLIS);
        return new EnergyMeasurement(
                MeasurementSchema.CURRENT_VERSION,
                EventIds.forMeasurement(r.sensorId(), ts),
                traceId,
                r.sensorId(),
                ts,
                receivedAt.truncatedTo(ChronoUnit.MILLIS),
                r.powerKw(),
                r.voltage(),
                r.temperature(),
                r.currentA(),
                r.frequencyHz(),
                r.state());
    }

    /** Jour UTC de la mesure : composante de la cle de partition Cassandra (sensor_id, day). */
    public LocalDate day() {
        return timestamp.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
