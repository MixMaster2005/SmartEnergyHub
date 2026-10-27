package com.smartenergy.storage.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
import com.smartenergy.contracts.measurement.MeasurementItem;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.contracts.measurement.SensorState;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.entity.MeasurementByDayEntity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Lecture de l'historique et de la derniere mesure (CDC section 5.4, decisions D-08, D-09).
 * Ecriture du stream : {@code CassandraMeasurementWriter} (etape 7) ; lecture : la presente service
 * (etape 8).
 *
 * <p><b>Historique</b> : plage demi-ouverte {@code [from, to)} (from inclus, to exclu), defauts
 * {@code to = maintenant}, {@code from = to - 24 h}. La lecture ne fait JAMAIS de scan : une
 * requete par jour UTC couvert par la plage ({@code sensor_id = ? AND day = ? AND measured_at >= ?
 * AND measured_at < ?}, LIMIT {@code limit + 1}), conformement a la partition
 * {@code (sensor_id, day)} de la decision D-08 et a l'interdiction de {@code IN} sur la cle de
 * partition (CDC section 8). Les requetes jour sont lancees EN PARALLELE (executeAsync du pilote,
 * decision D-23) puis les journees sont concatennees dans l'ordre (jour decroissant en
 * {@code desc}, croissant en {@code asc}) : chaque jour est deja trie par le clustering
 * {@code measured_at DESC} de la table, la fusion est donc une simple concatenation.
 *
 * <p><b>Pagination</b> : chaque jour est lu avec {@code limit + 1} lignes ; on coupe a
 * {@code limit} et, s'il reste des lignes, on renvoie {@code nextCursor} =
 * {@link HistoryCursor} du dernier element (jour + measured_at). Le curseur reprend la plage
 * demandee a la bonne place : borne stricte sur le jour du curseur, bornes normales sur les autres
 * jours. Un capteur sans donnee ou une plage sans mesure renvoie 200 avec {@code items} vide :
 * sensor-service n'est pas appele (pas de couplage, CDC section 5.5).
 *
 * <p><b>Derniere mesure</b> : lecture O(1) de {@code latest_measurement_by_sensor} (decision D-09),
 * enveloppee dans un cache-aside Redis {@code cache:latest:{sensorId}} (TTL 30 s, CDC section 6.4).
 * Redis indisponible : simple log warn, la lecture Cassandra reste servie (le cache est
 * best-effort). Capteur inconnu : {@code ApiException(MEASUREMENT_NOT_FOUND)} -> 404.
 */
@Service
public class MeasurementQueryService {

    private static final Logger log = LoggerFactory.getLogger(MeasurementQueryService.class);

    /** Plage maximale d'historique : 7 jours (CDC section 5.4). */
    private static final Duration MAX_RANGE = Duration.ofDays(7);

    private static final int DEFAULT_LIMIT = 100;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 1000;

    private final CqlSession session;
    private final CassandraTemplate cassandra;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    /** Horloge UTC injectable : en test, remplacer par une horloge fixe pour figer les defauts. */
    private final Clock clock = Clock.systemUTC();

    public MeasurementQueryService(
            CqlSession session,
            CassandraTemplate cassandra,
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.session = session;
        this.cassandra = cassandra;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Historique paginé d'un capteur.
     *
     * @param sensorId capteur (jamais verifie dans sensor-service : capteur inconnu = 200 vide)
     * @param from borne inclusive (ISO-8601), defaut to - 24 h
     * @param to borne EXCLUSIVE (ISO-8601), defaut maintenant
     * @param limit nombre maximal d'elements, defaut 100, plage [1, 1000]
     * @param cursor {@link HistoryCursor#encode()} d'un appel precedent, optionnel
     * @param order {@code desc} (defaut) ou {@code asc}
     * @return 200 toujours ; nextCursor non null s'il reste des pages
     * @throws ApiException(INVALID_PARAMETER) parametre invalide (limit, order, to <= from,
     *     curseur illisible ou hors plage)
     * @throws ApiException(RANGE_TOO_LARGE) plage de plus de 7 jours
     */
    public MeasurementHistoryResponse history(
            String sensorId, Instant from, Instant to, Integer limit, String cursor, String order) {

        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(24));
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "'to' doit etre strictement posterieur a 'from'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE,
                    "Plage de lecture de plus de 7 jours : paginez avec un curseur");
        }
        int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;
        if (effectiveLimit < MIN_LIMIT || effectiveLimit > MAX_LIMIT) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "limit doit etre compris entre " + MIN_LIMIT + " et " + MAX_LIMIT);
        }
        boolean asc = parseOrder(order);
        HistoryCursor resume = cursor != null ? HistoryCursor.decode(cursor) : null;

        // Bornes UTC : [from, to) couvre les jours fromDay..lastDay (to exclu : si to est
        // minuit pile, son jour n'est pas couvert).
        LocalDate fromDay = effectiveFrom.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate lastDay = effectiveTo.minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate();
        if (resume != null && (resume.day().isBefore(fromDay) || resume.day().isAfter(lastDay))) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "Le curseur ne correspond pas a la plage from/to demandee");
        }

        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = fromDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            days.add(d);
        }
        if (!asc) {
            days.sort(Comparator.reverseOrder());
        }

        // UNE requete par jour, en parallele ; limit + 1 lignes pour detecter une page suivante.
        List<CompletableFuture<List<MeasurementByDayEntity>>> futures = days.stream()
                .map(day -> queryDay(sensorId, day, effectiveFrom, effectiveTo, resume, asc, effectiveLimit + 1))
                .toList();
        List<MeasurementByDayEntity> merged = joinInOrder(futures, days.size());

        List<MeasurementItem> items = merged.stream().limit(effectiveLimit)
                .map(MeasurementQueryService::toItem).toList();
        String nextCursor = merged.size() > effectiveLimit
                ? new HistoryCursor(merged.get(effectiveLimit - 1).getKey().getDay(),
                        merged.get(effectiveLimit - 1).getKey().getMeasuredAt())
                        .encode()
                : null;
        return new MeasurementHistoryResponse(sensorId, effectiveFrom, effectiveTo, items, nextCursor);
    }

    /**
     * Derniere mesure connue d'un capteur (cache-aside Redis, TTL 30 s).
     *
     * @return 200 la mesure
     * @throws ApiException(MEASUREMENT_NOT_FOUND) capteur sans aucune mesure -> 404
     */
    public MeasurementItem latest(String sensorId) {
        String cacheKey = RedisKeys.cacheLatest(sensorId);
        String cached = readCache(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, MeasurementItem.class);
            } catch (JsonProcessingException e) {
                log.warn("Entree de cache latest illisible pour {}, lecture Cassandra", sensorId);
            }
        }
        SimpleStatement selectLatest = SimpleStatement.newInstance(
                "SELECT * FROM latest_measurement_by_sensor WHERE sensor_id = ?", sensorId);
        LatestMeasurementEntity entity = cassandra.selectOne(selectLatest, LatestMeasurementEntity.class);
        if (entity == null) {
            throw new ApiException(ErrorCode.MEASUREMENT_NOT_FOUND,
                    "Aucune mesure connue pour le capteur " + sensorId);
        }
        MeasurementItem item = toItem(entity);
        writeCache(cacheKey, item);
        return item;
    }

    // ------------------------------------------------------------------ historique

    /** Les bornes de plage (from inclus, to exclu) valent sur TOUS les jours, curseur ou non. */
    private CompletableFuture<List<MeasurementByDayEntity>> queryDay(
            String sensorId, LocalDate day, Instant from, Instant to, HistoryCursor resume, boolean asc, int limit) {

        // Sur le jour du curseur seulement, la borne devient stricte : la reprise reprend APRES
        // le dernier element deja renvoye (pas de doublon, pas de trou).
        boolean onCursorDay = resume != null && resume.day().equals(day);
        Instant lower = onCursorDay && asc ? resume.lastMeasuredAt() : from;
        Instant upper = onCursorDay && !asc ? resume.lastMeasuredAt() : to;

        StringBuilder cql = new StringBuilder("SELECT * FROM measurements_by_sensor_day")
                .append(" WHERE sensor_id = ? AND day = ? AND measured_at")
                .append(asc && onCursorDay ? " > ?" : " >= ?")
                .append(" AND measured_at < ?")
                .append(asc ? " ORDER BY measured_at ASC" : "")
                .append(" LIMIT ?");
        SimpleStatement statement =
                SimpleStatement.newInstance(cql.toString(), sensorId, day, lower, upper, limit);
        return session.executeAsync(statement)
                .toCompletableFuture()
                .thenCompose(resultSet -> readAllPages(resultSet));
    }

    /** Copie toutes les pages du ResultSet (le pilote ne les charge pas toutes d'emblee),
     *  en composition (pas de join dans le thread du pilote). */
    private CompletableFuture<List<MeasurementByDayEntity>> readAllPages(AsyncResultSet current) {
        List<MeasurementByDayEntity> rows = new ArrayList<>();
        current.currentPage().forEach(row -> rows.add(cassandra.getConverter()
                .read(MeasurementByDayEntity.class, row)));
        if (!current.hasMorePages()) {
            return CompletableFuture.completedFuture(rows);
        }
        return current.fetchNextPage().toCompletableFuture()
                .thenCompose(next -> readAllPages(next).thenApply(more -> {
                    rows.addAll(more);
                    return rows;
                }));
    }

    /** Attente groupee des requetes jours (une erreur = 500, pas de reponse partielle). */
    private static List<MeasurementByDayEntity> joinInOrder(
            List<CompletableFuture<List<MeasurementByDayEntity>>> futures, int expectedDays) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
        List<MeasurementByDayEntity> merged = new ArrayList<>();
        for (CompletableFuture<List<MeasurementByDayEntity>> future : futures) {
            merged.addAll(future.join());
        }
        return merged;
    }

    /** "desc" (defaut) ou "asc" ; toute autre valeur est un 400 INVALID_PARAMETER. */
    private static boolean parseOrder(String order) {
        if (order == null || order.isBlank() || "desc".equalsIgnoreCase(order)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(order)) {
            return true;
        }
        throw new ApiException(ErrorCode.INVALID_PARAMETER, "order doit valoir 'asc' ou 'desc'");
    }

    // ------------------------------------------------------------------ conversions

    /** Ligne Cassandra -> element de reponse (champs absents omis, CDC section 5.4). */
    private static MeasurementItem toItem(MeasurementByDayEntity e) {
        return new MeasurementItem(
                e.getKey().getMeasuredAt(),
                Objects.requireNonNullElse(e.getPowerKw(), 0.0),
                Objects.requireNonNullElse(e.getVoltage(), 0.0),
                e.getTemperature(),
                e.getCurrentA(),
                e.getFrequencyHz(),
                e.getState() != null ? SensorState.valueOf(e.getState()) : null);
    }

    /** Ligne "derniere mesure" -> element de reponse. */
    private static MeasurementItem toItem(LatestMeasurementEntity e) {
        return new MeasurementItem(
                e.getMeasuredAt(),
                Objects.requireNonNullElse(e.getPowerKw(), 0.0),
                Objects.requireNonNullElse(e.getVoltage(), 0.0),
                e.getTemperature(),
                e.getCurrentA(),
                e.getFrequencyHz(),
                e.getState() != null ? SensorState.valueOf(e.getState()) : null);
    }

    // ------------------------------------------------------------------ cache-aside

    /** Lecture du cache : Redis KO = warn + cache miss (le cache est best-effort). */
    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Cache Redis indisponible ({}), lecture Cassandra directe", e.toString());
            return null;
        }
    }

    /** Ecriture du cache : echec silencieux, la donnee reste lisible depuis Cassandra. */
    private void writeCache(String key, MeasurementItem item) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(item), RedisKeys.CACHE_LATEST_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Ecriture du cache latest impossible : {}", e.toString());
        }
    }
}
