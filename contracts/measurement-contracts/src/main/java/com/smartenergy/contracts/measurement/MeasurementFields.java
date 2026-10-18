package com.smartenergy.contracts.measurement;

/** Noms des champs d'une entree du stream (tous les champs sont des chaines Redis). */
public final class MeasurementFields {
    private MeasurementFields() {}

    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String EVENT_ID = "eventId";
    public static final String TRACE_ID = "traceId";
    public static final String SENSOR_ID = "sensorId";
    public static final String TIMESTAMP = "timestamp";
    public static final String RECEIVED_AT = "receivedAt";
    public static final String POWER_KW = "powerKw";
    public static final String VOLTAGE = "voltage";
    public static final String TEMPERATURE = "temperature";
    public static final String CURRENT_A = "currentA";
    public static final String FREQUENCY_HZ = "frequencyHz";
    public static final String STATE = "state";
}
