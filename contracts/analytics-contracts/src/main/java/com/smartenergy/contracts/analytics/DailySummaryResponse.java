package com.smartenergy.contracts.analytics;

/** GET /api/sensors/{id}/daily-summary (alias /api/analytics/{sensorId}/daily-summary). */
public record DailySummaryResponse(
        String sensorId,
        String date,
        long count,
        double avgPowerKw,
        double minPowerKw,
        double maxPowerKw,
        double energyKwh,
        long thresholdExceededCount,
        long anomalyCount) {}
