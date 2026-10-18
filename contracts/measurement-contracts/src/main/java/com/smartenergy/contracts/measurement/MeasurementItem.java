package com.smartenergy.contracts.measurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Element d'historique renvoye par storage-service (champs absents omis). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeasurementItem(
        Instant timestamp,
        double powerKw,
        double voltage,
        Double temperature,
        Double currentA,
        Double frequencyHz,
        SensorState state) {}
