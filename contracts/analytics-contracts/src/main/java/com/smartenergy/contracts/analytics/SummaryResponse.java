package com.smartenergy.contracts.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * GET /api/analytics/{sensorId}/summary : indicateurs sur [effectiveFrom, effectiveTo).
 * Granularite HORAIRE : les bornes demandees sont alignees sur les heures pleines (decision D-13).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummaryResponse(
        String sensorId,
        Instant effectiveFrom,
        Instant effectiveTo,
        long count,
        double avgPowerKw,
        double minPowerKw,
        double maxPowerKw,
        double energyKwh,
        Double thresholdKw,
        long thresholdExceededCount,
        long anomalyCount) {}
