package com.smartenergy.contracts.measurement;

import com.smartenergy.contracts.common.SensorIdPattern;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * Corps de POST /api/measurements (CDC section 5.2).
 * Unites : kW, V, degres C, A, Hz. Date : ISO-8601 UTC.
 * Les regles TEMPORELLES (pas plus de 5 min dans le futur, pas plus vieux que 24 h) dependent de
 * l'horloge : elles sont appliquees par le collector (MeasurementTimeValidator), pas ici.
 */
public record EnergyMeasurementRequest(
        @NotBlank(message = "ne doit pas etre vide")
        @Pattern(regexp = SensorIdPattern.REGEX, message = SensorIdPattern.MESSAGE)
        String sensorId,

        @NotNull(message = "est obligatoire")
        Instant timestamp,

        @NotNull(message = "est obligatoire")
        @DecimalMin(value = "0.0", message = "doit etre superieur ou egal a 0")
        @DecimalMax(value = "1000.0", message = "doit etre inferieur ou egal a 1000 kW")
        Double powerKw,

        @NotNull(message = "est obligatoire")
        @DecimalMin(value = "0.0", message = "doit etre superieur ou egal a 0")
        @DecimalMax(value = "500.0", message = "doit etre inferieur ou egal a 500 V")
        Double voltage,

        @DecimalMin(value = "-50.0", message = "doit etre superieur ou egal a -50")
        @DecimalMax(value = "100.0", message = "doit etre inferieur ou egal a 100")
        Double temperature,

        @DecimalMin(value = "0.0", message = "doit etre superieur ou egal a 0")
        @DecimalMax(value = "2000.0", message = "doit etre inferieur ou egal a 2000 A")
        Double currentA,

        @DecimalMin(value = "40.0", message = "doit etre superieur ou egal a 40 Hz")
        @DecimalMax(value = "70.0", message = "doit etre inferieur ou egal a 70 Hz")
        Double frequencyHz,

        SensorState state) {}
