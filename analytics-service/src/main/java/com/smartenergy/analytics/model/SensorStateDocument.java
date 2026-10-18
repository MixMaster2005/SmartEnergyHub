package com.smartenergy.analytics.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * analytics_db.sensor_state : un document par capteur (_id = sensorId). Porte l'etat courant et les
 * statistiques de Welford du score z (n, mean, m2) - CDC sections 6.3 et 6.5.
 */
@Document("sensor_state")
public record SensorStateDocument(
        @Id String sensorId,
        Instant lastMeasuredAt,
        Double lastPowerKw,
        /** Instant de la derniere mesure ayant servi au calcul d'energie (pas de temps suivant). */
        Instant lastEnergyBasisTs,
        long n,
        double mean,
        double m2) {}
