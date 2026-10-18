package com.smartenergy.contracts.measurement;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Reponse de GET /api/measurements/{sensorId} : plage [from, to), pagination par curseur opaque.
 * {@code nextCursor} vaut null en fin de liste.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MeasurementHistoryResponse(
        String sensorId,
        Instant from,
        Instant to,
        List<MeasurementItem> items,
        String nextCursor) {}
