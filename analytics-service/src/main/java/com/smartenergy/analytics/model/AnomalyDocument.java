package com.smartenergy.analytics.model;

import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * analytics_db.anomalies. Index unique (eventId, type) : une anomalie par evenement et par type, ce qui rend
 * l'insertion idempotente. eventId est stocke en texte (evite la configuration de representation des UUID).
 */
@Document("anomalies")
public record AnomalyDocument(
        @Id String id,
        String sensorId,
        Instant timestamp,
        String eventId,
        AnomalyType type,
        Severity severity,
        double powerKw,
        Double thresholdKw,
        /** SENSOR ou DEFAULT. */
        String thresholdSource,
        Double zScore,
        Instant detectedAt) {}
