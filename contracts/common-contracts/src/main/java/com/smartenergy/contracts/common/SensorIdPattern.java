package com.smartenergy.contracts.common;

/** Format unique d'identifiant capteur, partage par tous les contrats (ex. SENSOR-001). */
public final class SensorIdPattern {
    private SensorIdPattern() {}

    public static final String REGEX = "^[A-Z0-9-]{3,32}$";
    public static final String MESSAGE = "doit respecter le format ^[A-Z0-9-]{3,32}$ (ex. SENSOR-001)";
}
