package com.smartenergy.analytics.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** analytics_db.daily_aggregates : _id = "sensorId|yyyy-MM-dd", date = jour UTC (texte). */
@Document("daily_aggregates")
public record DailyAggregateDocument(
        @Id String id,
        String sensorId,
        String date,
        long count,
        double sumPowerKw,
        double minPowerKw,
        double maxPowerKw,
        double energyKwh,
        long thresholdExceededCount,
        long anomalyCount,
        Instant updatedAt) {}
