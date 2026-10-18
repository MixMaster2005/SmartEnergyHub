package com.smartenergy.contracts.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** GET /api/analytics/{sensorId}/forecast (extension). metrics : par exemple {"mae": 0.21, "rmse": 0.30}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForecastResponse(
        String sensorId,
        String model,
        Instant generatedAt,
        int horizonHours,
        List<ForecastPoint> points,
        Map<String, Double> metrics) {}
