package com.smartenergy.simulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Parametres du simulateur (prefixe smartenergy.simulator). Modele : P(t) = Pbase + A.sin(2.pi.t/T + phase) + bruit. */
@ConfigurationProperties(prefix = "smartenergy.simulator")
public record SimulatorProperties(
        @DefaultValue("http://localhost:8081") String targetUrl,
        @DefaultValue("http://localhost:8084") String sensorServiceUrl,
        /** Enregistre les capteurs simules dans sensor-service au demarrage (409 ignore). */
        @DefaultValue("false") boolean registerSensors,
        @DefaultValue("5") int sensors,
        @DefaultValue("PT2S") Duration interval,
        /** PT0S = sans fin. */
        @DefaultValue("PT2M") Duration duration,
        /** Graine : memes valeurs a chaque execution (reproductibilite). */
        @DefaultValue("42") long seed,
        @DefaultValue("3.5") double pBaseKw,
        @DefaultValue("1.0") double amplitudeKw,
        /** Periode T du sinus, en secondes (comprimee pour voir un cycle pendant un test court). */
        @DefaultValue("120") int periodSeconds,
        @DefaultValue("0.1") double noiseSigmaKw,
        @DefaultValue("230") double voltageBase,
        @DefaultValue("25") double temperatureBase,
        /** Seuil maxPowerKw des capteurs enregistres : doit etre inferieur a factor x pBase pour que le pic soit detecte. */
        @DefaultValue("8.0") double maxPowerKw,
        /** Part des mesures volontairement invalides (scenario 2). */
        @DefaultValue("0.0") double invalidRate,
        /** Part des mesures renvoyees une deuxieme fois (doublons). */
        @DefaultValue("0.0") double duplicateRate,
        @DefaultValue Anomaly anomaly) {

    public record Anomaly(
            @DefaultValue("") String sensor,
            @DefaultValue("60") int atSeconds,
            @DefaultValue("10") int durationSeconds,
            @DefaultValue("3.0") double factor) {
        public boolean enabled() { return sensor != null && !sensor.isBlank(); }
    }
}
