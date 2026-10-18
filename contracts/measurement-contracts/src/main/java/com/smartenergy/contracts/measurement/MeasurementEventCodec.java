package com.smartenergy.contracts.measurement;

import static com.smartenergy.contracts.measurement.MeasurementFields.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Conversion EnergyMeasurement <-> champs texte du stream Redis. UNIQUE point de (de)serialisation :
 * le collector l'utilise pour ecrire, storage et analytics pour lire.
 *
 * Regles : champs optionnels ABSENTS omis (jamais de chaine vide a l'ecriture, chaine vide = absent a la
 * lecture), point decimal independant de la locale, dates UTC avec millisecondes, champs inconnus ignores.
 */
public final class MeasurementEventCodec {
    private MeasurementEventCodec() {}

    public static Map<String, String> toFields(EnergyMeasurement m) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(SCHEMA_VERSION, Integer.toString(m.schemaVersion()));
        f.put(EVENT_ID, m.eventId().toString());
        f.put(TRACE_ID, m.traceId());
        f.put(SENSOR_ID, m.sensorId());
        f.put(TIMESTAMP, MeasurementSchema.TIMESTAMP_FORMAT.format(m.timestamp()));
        f.put(RECEIVED_AT, MeasurementSchema.TIMESTAMP_FORMAT.format(m.receivedAt()));
        f.put(POWER_KW, Double.toString(m.powerKw()));
        f.put(VOLTAGE, Double.toString(m.voltage()));
        putIfPresent(f, TEMPERATURE, m.temperature());
        putIfPresent(f, CURRENT_A, m.currentA());
        putIfPresent(f, FREQUENCY_HZ, m.frequencyHz());
        if (m.state() != null) f.put(STATE, m.state().name());
        return f;
    }

    public static EnergyMeasurement fromFields(Map<String, String> f) {
        int version = parseInt(required(f, SCHEMA_VERSION), SCHEMA_VERSION);
        if (version < MeasurementSchema.MIN_SUPPORTED_VERSION || version > MeasurementSchema.CURRENT_VERSION) {
            throw new MalformedEventException("schemaVersion non supporte : " + version);
        }
        try {
            return new EnergyMeasurement(
                    version,
                    UUID.fromString(required(f, EVENT_ID)),
                    required(f, TRACE_ID),
                    required(f, SENSOR_ID),
                    Instant.parse(required(f, TIMESTAMP)),
                    Instant.parse(required(f, RECEIVED_AT)),
                    parseDouble(required(f, POWER_KW), POWER_KW),
                    parseDouble(required(f, VOLTAGE), VOLTAGE),
                    optionalDouble(f, TEMPERATURE),
                    optionalDouble(f, CURRENT_A),
                    optionalDouble(f, FREQUENCY_HZ),
                    optionalState(f));
        } catch (MalformedEventException e) {
            throw e;
        } catch (RuntimeException e) { // IllegalArgumentException, DateTimeParseException...
            throw new MalformedEventException("evenement illisible : " + e.getMessage(), e);
        }
    }

    private static void putIfPresent(Map<String, String> f, String name, Double value) {
        if (value != null) f.put(name, Double.toString(value));
    }

    private static String required(Map<String, String> f, String name) {
        String v = f.get(name);
        if (v == null || v.isBlank()) throw new MalformedEventException("champ obligatoire manquant : " + name);
        return v;
    }

    private static int parseInt(String v, String name) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new MalformedEventException("entier invalide pour " + name + " : " + v, e);
        }
    }

    private static double parseDouble(String v, String name) {
        try {
            double d = Double.parseDouble(v);
            if (!Double.isFinite(d)) throw new MalformedEventException("nombre non fini pour " + name);
            return d;
        } catch (NumberFormatException e) {
            throw new MalformedEventException("nombre invalide pour " + name + " : " + v, e);
        }
    }

    private static Double optionalDouble(Map<String, String> f, String name) {
        String v = f.get(name);
        return (v == null || v.isBlank()) ? null : parseDouble(v, name);
    }

    private static SensorState optionalState(Map<String, String> f) {
        String v = f.get(STATE);
        if (v == null || v.isBlank()) return null;
        try {
            return SensorState.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("state inconnu : " + v, e);
        }
    }
}
