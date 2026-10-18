package com.smartenergy.contracts.measurement;

import java.time.Duration;
import java.util.UUID;

/**
 * Cles Redis hors stream (CDC section 6.4). Convention : {usage}:{service}:{identifiant}.
 * Aucun service ne fabrique une cle "a la main" : tout passe par cette classe.
 */
public final class RedisKeys {
    private RedisKeys() {}

    /** Cache du seuil capteur (proprietaire : analytics-service). */
    public static String cacheSensor(String sensorId) { return "cache:sensor:" + sensorId; }
    public static final Duration CACHE_SENSOR_TTL = Duration.ofSeconds(60);

    /** Cache-aside de la derniere mesure (proprietaire : storage-service, optionnel). */
    public static String cacheLatest(String sensorId) { return "cache:latest:" + sensorId; }
    public static final Duration CACHE_LATEST_TTL = Duration.ofSeconds(30);

    /** Deduplication des evenements cote analytics (verifier avant, marquer APRES ecriture reussie). */
    public static String dedupAnalytics(UUID eventId) { return "dedup:analytics:" + eventId; }
    public static final Duration DEDUP_TTL = Duration.ofHours(24);
}
