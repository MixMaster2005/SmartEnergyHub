package com.smartenergy.analytics.service;

import com.smartenergy.analytics.client.SensorClient;
import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.contracts.analytics.AnomalyResponse;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.DailySummaryResponse;
import com.smartenergy.contracts.analytics.ExceedanceResponse;
import com.smartenergy.contracts.analytics.SummaryResponse;
import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Lecture des indicateurs analytics (CDC section 5.4, decisions D-13 et D-17). L'ECRITURE des
 * agregats appartient a {@code AggregateWriter} (etape 9) : ce service ne fait que lire
 * {@code hourly_aggregates}, {@code daily_aggregates}, {@code anomalies} et {@code sensor_state}.
 *
 * <p><b>summary</b> : granularite HORAIRE (decision D-13). Les bornes demandees sont ALIGNEES sur
 * les heures pleines avant lecture : {@code effectiveFrom} = from tronque a l'heure,
 * {@code effectiveTo} = to arrondi a l'heure superieure. Les agregats sont ADDITIFS : la moyenne
 * se recalcule a la lecture ({@code somme(sumPowerKw) / somme(count)}), JAMAIS la moyenne des
 * moyennes (une heure a 2 mesures et une heure a 400 mesures ne pèsent pas pareil). min/max sont
 * les min des min et max des max ; les compteurs sont sommes. Plage maximale 31 jours.
 * {@code thresholdKw} vient du capteur via {@link SensorClient} (cache, repli sur le seuil
 * par defaut). Aucun agregat sur la periode -> {@code ApiException(NO_DATA)} (404).
 *
 * <p><b>dailySummary</b> : lecture ponctuelle par cle ({@code _id = "sensorId|yyyy-MM-dd"}),
 * defaut = jour UTC courant ; 404 {@code NO_DATA} si absent. Meme regle de moyenne a la lecture.
 *
 * <p><b>anomalies</b> : liste paginee, plus recentes d'abord (tri {@code timestamp desc}),
 * filtre de type optionnel. {@code PageResponse} standard (page indexee a 0).
 *
 * <p><b>exceedances</b> : deux lectures selon les parametres (CDC 5.4) :
 * sans {@code from}/{@code to} -> etat courant depuis {@code sensor_state}
 * ({@code lastPowerKw > threshold}) ; avec dates -> granularite QUOTIDIENNE depuis
 * {@code daily_aggregates} ({@code maxPowerKw > threshold} sur la periode, plage maximale
 * 31 jours). Les comparaisons de date texte {@code yyyy-MM-dd} sont lexicographiques, donc
 * ordonnees chronologiquement.
 *
 * <p>Test croise de validation (guide, etape 8) : le {@code count} de summary doit egaler le
 * nombre de mesures Cassandra de la meme plage (ecarts tolerables si des mesures sont en attente
 * dans le stream).
 */
@Service
public class AnalyticsQueryService {

    /** Plage maximale de summary / exceedances dates : 31 jours (CDC section 5.4). */
    private static final Duration MAX_RANGE = Duration.ofDays(31);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MongoTemplate mongo;
    private final SensorClient sensorClient;
    private final Clock clock;

    public AnalyticsQueryService(MongoTemplate mongo, SensorClient sensorClient, Clock clock) {
        this.mongo = mongo;
        this.sensorClient = sensorClient;
        this.clock = clock;
    }

    /**
     * Indicateurs horaires agreges d'un capteur sur [from, to) aligne sur les heures pleines.
     *
     * @throws ApiException(INVALID_PARAMETER) to &lt;= from
     * @throws ApiException(RANGE_TOO_LARGE) plage de plus de 31 jours
     * @throws ApiException(NO_DATA) aucun agregat horaire sur la periode -> 404
     */
    public SummaryResponse summary(String sensorId, Instant from, Instant to) {
        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(24));
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "'to' doit etre strictement posterieur a 'from'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE, "Plage de plus de 31 jours : agregez par mois cote client");
        }
        // Alignement D-13 : [heure pleine inferieure, heure pleine superieure).
        Instant hourFrom = effectiveFrom.truncatedTo(ChronoUnit.HOURS);
        Instant hourToTruncated = effectiveTo.truncatedTo(ChronoUnit.HOURS);
        Instant hourTo = hourToTruncated.equals(effectiveTo) ? effectiveTo : hourToTruncated.plusSeconds(3600);

        List<HourlyAggregateDocument> docs = mongo.find(query(Criteria.where("sensorId").is(sensorId)
                        .and("hour").gte(hourFrom).lt(hourTo)),
                HourlyAggregateDocument.class);
        if (docs.isEmpty()) {
            throw new ApiException(ErrorCode.NO_DATA,
                    "Aucun agregat horaire pour " + sensorId + " sur la periode demandee");
        }

        long count = 0;
        double sumPower = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double energyKwh = 0.0;
        long exceeded = 0;
        long anomalies = 0;
        for (HourlyAggregateDocument d : docs) {
            count += d.count();
            sumPower += d.sumPowerKw();
            min = Math.min(min, d.minPowerKw());
            max = Math.max(max, d.maxPowerKw());
            energyKwh += d.energyKwh();
            exceeded += d.thresholdExceededCount();
            anomalies += d.anomalyCount();
        }
        // Une heure ne peut pas avoir count = 0 en pratique ; garde-fou anti NaN.
        double avg = count > 0 ? sumPower / count : 0.0;
        if (count == 0) {
            min = 0.0;
            max = 0.0;
        }
        Double thresholdKw = sensorClient.thresholdFor(sensorId).maxPowerKw();
        return new SummaryResponse(sensorId, hourFrom, hourTo, count, avg, min, max, energyKwh,
                thresholdKw, exceeded, anomalies);
    }

    /**
     * Agregat quotidien d'un capteur (decision D-17 : appartient a analytics).
     *
     * @param date defaut = jour UTC courant
     * @throws ApiException(NO_DATA) pas d'agregat pour ce jour -> 404
     */
    public DailySummaryResponse dailySummary(String sensorId, LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        DailyAggregateDocument doc = mongo.findById(dailyId(sensorId, effectiveDate), DailyAggregateDocument.class);
        if (doc == null) {
            throw new ApiException(ErrorCode.NO_DATA,
                    "Aucun agregat quotidien pour " + sensorId + " le " + effectiveDate);
        }
        double avg = doc.count() > 0 ? doc.sumPowerKw() / doc.count() : 0.0;
        return new DailySummaryResponse(doc.sensorId(), doc.date(), doc.count(), avg,
                doc.minPowerKw(), doc.maxPowerKw(), doc.energyKwh(), doc.thresholdExceededCount(),
                doc.anomalyCount());
    }

    /**
     * Anomalies d'un capteur, plus recentes d'abord, paginees.
     *
     * @param type filtre optionnel (THRESHOLD, ZSCORE, FORECAST_RESIDUAL)
     * @throws ApiException(INVALID_PARAMETER) page &lt; 0 ou size hors [1, 100], ou from &gt;= to
     */
    public PageResponse<AnomalyResponse> anomalies(
            String sensorId, Instant from, Instant to, AnomalyType type, int page, int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "page doit etre superieur ou egal a 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "size doit etre compris entre 1 et " + MAX_PAGE_SIZE);
        }
        Criteria criteria = Criteria.where("sensorId").is(sensorId);
        if (from != null || to != null) {
            if (from != null && to != null && !from.isBefore(to)) {
                throw new ApiException(ErrorCode.INVALID_PARAMETER, "'to' doit etre posterieur a 'from'");
            }
            if (from != null) {
                criteria = criteria.and("timestamp").gte(from);
            }
            if (to != null) {
                criteria = criteria.and("timestamp").lte(to);
            }
        }
        if (type != null) {
            criteria = criteria.and("type").is(type);
        }
        Query find = query(criteria).with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .skip((long) page * size)
                .limit(size);
        List<AnomalyResponse> items = mongo.find(find, AnomalyDocument.class).stream()
                .map(AnalyticsQueryService::toResponse).toList();
        long total = mongo.count(query(criteria), AnomalyDocument.class);
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * Capteurs au dela d'un seuil. Sans dates : etat courant (derniere puissance connue) ;
     * avec dates : maximum des agregats quotidiens de la periode.
     *
     * @param threshold seuil en kW (obligatoire, garanti par le controleur)
     * @throws ApiException(INVALID_PARAMETER) plage invalide ou de plus de 31 jours
     */
    public List<ExceedanceResponse> exceedances(double threshold, Instant from, Instant to) {
        if (from == null && to == null) {
            // Etat courant : sensor_state porte lastPowerKw par capteur.
            List<SensorStateDocument> states = mongo.find(
                    query(Criteria.where("lastPowerKw").gt(threshold)), SensorStateDocument.class);
            return states.stream()
                    .sorted((a, b) -> Double.compare(b.lastPowerKw(), a.lastPowerKw()))
                    .map(s -> new ExceedanceResponse(s.sensorId(), threshold, s.lastPowerKw(),
                            s.lastMeasuredAt(), null))
                    .toList();
        }
        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(24));
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "'to' doit etre strictement posterieur a 'from'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE, "Plage de plus de 31 jours");
        }
        // daily_aggregates.date est un texte "yyyy-MM-dd" : la comparaison lexicographique
        // est chronologique, les bornes sont les jours UTC inclus.
        String dayFrom = effectiveFrom.atZone(ZoneOffset.UTC).toLocalDate().toString();
        String dayTo = effectiveTo.minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate().toString();
        List<DailyAggregateDocument> docs = mongo.find(query(Criteria.where("date").gte(dayFrom).lte(dayTo)
                        .and("maxPowerKw").gt(threshold)),
                DailyAggregateDocument.class);
        return docs.stream()
                .sorted((a, b) -> Double.compare(b.maxPowerKw(), a.maxPowerKw()))
                .map(d -> new ExceedanceResponse(d.sensorId(), threshold, null, null, d.maxPowerKw()))
                .toList();
    }

    // ------------------------------------------------------------------ utilitaires

    private static Query query(Criteria criteria) {
        return new Query(criteria);
    }

    /** Cle de daily_aggregates : "sensorId|yyyy-MM-dd" (CDC section 6.3). */
    static String dailyId(String sensorId, LocalDate date) {
        return sensorId + "|" + date;
    }

    private static AnomalyResponse toResponse(AnomalyDocument d) {
        return new AnomalyResponse(d.id(), d.sensorId(), d.timestamp(), d.eventId(), d.type(),
                d.severity(), d.powerKw(), d.thresholdKw(), d.thresholdSource(), d.zScore(),
                d.detectedAt());
    }
}
