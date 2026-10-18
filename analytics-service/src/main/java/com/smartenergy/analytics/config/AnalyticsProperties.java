package com.smartenergy.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Parametres smartenergy.analytics.* (CDC sections 6.5 et 11). */
@ConfigurationProperties(prefix = "smartenergy.analytics")
public record AnalyticsProperties(
        @DefaultValue("http://localhost:8084") String sensorServiceUrl,
        /** Seuil utilise quand le capteur est inconnu ou sensor-service injoignable. */
        @DefaultValue("10.0") double defaultMaxPowerKw,
        @DefaultValue("2") int expectedIntervalSeconds,
        /** Plafond du pas de temps pour l'energie : expectedIntervalSeconds x energyGapFactor. */
        @DefaultValue("5") int energyGapFactor,
        @DefaultValue Zscore zscore,
        @DefaultValue Forecast forecast) {

    public record Zscore(@DefaultValue("3.0") double threshold, @DefaultValue("30") int minSamples) {}

    public record Forecast(@DefaultValue("false") boolean enabled, @DefaultValue("models/energy.onnx") String modelPath) {}
}
