package com.smartenergy.contracts.measurement;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Identifiant d'evenement DETERMINISTE (decision D-07) : UUID v5 de "sensorId|timestamp(ms)".
 * Une meme mesure rejouee ou dupliquee donne le meme eventId -> deduplication triviale.
 */
public final class EventIds {
    private EventIds() {}

    /** Espace de noms fixe du projet : ne JAMAIS le modifier (changerait tous les eventId). */
    public static final UUID NAMESPACE = UUID.fromString("5b1a2c4e-8f3d-4a6b-9c7e-1d2f3a4b5c6d");

    public static UUID forMeasurement(String sensorId, Instant timestamp) {
        String ts = MeasurementSchema.TIMESTAMP_FORMAT.format(timestamp.truncatedTo(ChronoUnit.MILLIS));
        return uuidV5(NAMESPACE, sensorId + "|" + ts);
    }

    /** UUID v5 (RFC 4122 / 9562) : SHA-1 de (namespace || name), bits de version et de variante positionnes. */
    public static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16);
            ns.putLong(namespace.getMostSignificantBits());
            ns.putLong(namespace.getLeastSignificantBits());
            sha1.update(ns.array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50); // version 5
            h[8] = (byte) ((h[8] & 0x3f) | 0x80); // variante RFC 4122
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponible", e);
        }
    }
}
