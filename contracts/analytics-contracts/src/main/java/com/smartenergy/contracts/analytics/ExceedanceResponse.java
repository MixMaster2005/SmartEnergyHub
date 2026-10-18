package com.smartenergy.contracts.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Element de GET /api/analytics/exceedances?threshold=. Sans from/to : etat courant (lastPowerKw) ;
 * avec from/to : maxPowerKw des agregats quotidiens de la periode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExceedanceResponse(
        String sensorId,
        double thresholdKw,
        Double lastPowerKw,
        Instant lastMeasuredAt,
        Double maxPowerKw) {}
