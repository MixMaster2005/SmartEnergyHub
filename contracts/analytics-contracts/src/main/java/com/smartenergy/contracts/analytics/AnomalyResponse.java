package com.smartenergy.contracts.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Element de GET /api/analytics/{sensorId}/anomalies. thresholdSource : SENSOR ou DEFAULT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnomalyResponse(
        String anomalyId,
        String sensorId,
        Instant timestamp,
        String eventId,
        AnomalyType type,
        Severity severity,
        double powerKw,
        Double thresholdKw,
        String thresholdSource,
        Double zScore,
        Instant detectedAt) {}
