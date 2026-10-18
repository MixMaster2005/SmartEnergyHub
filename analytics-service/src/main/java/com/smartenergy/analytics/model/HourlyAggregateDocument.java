package com.smartenergy.analytics.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * analytics_db.hourly_aggregates : _id = "sensorId|yyyy-MM-ddTHH". Agregats ADDITIFS : la moyenne
 * se calcule a la lecture (sumPowerKw / count).
 */
@Document("hourly_aggregates")
public record HourlyAggregateDocument(
        @Id String id,
        String sensorId,
        Instant hour,
        long count,
        double sumPowerKw,
        double minPowerKw,
        double maxPowerKw,
        double energyKwh,
        long thresholdExceededCount,
        long anomalyCount,
        Instant updatedAt) {}
