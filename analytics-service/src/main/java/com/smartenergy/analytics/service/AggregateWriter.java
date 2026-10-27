package com.smartenergy.analytics.service;

import com.mongodb.DuplicateKeyException;
import com.smartenergy.analytics.client.SensorClient;
import com.smartenergy.analytics.model.AnomalyDocument;
import com.smartenergy.analytics.model.DailyAggregateDocument;
import com.smartenergy.analytics.model.HourlyAggregateDocument;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.Severity;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Ecriture des agregats analytics dans {@code analytics_db} (CDC sections 6.3 et 7.5, decisions
 * D-13 et D-14). Guide : etape 6 (agregat quotidien) et etape 9.
 *
 * <p>Recoit un lot de mesures <b>deja evaluees</b> par {@code AnalyticsRules} (le consumer est le
 * seul a parler aux regles et a lire {@code sensor_state}) et l'applique aux quatre collections :
 *
 * <ul>
 *   <li><b>Micro-batch en memoire (D-14)</b> : le lot est regroupe par capteur, puis par heure et
 *       par jour UTC ; une seule ecriture Mongo par document et par lot (pas une par mesure), ce
 *       qui reduit fortement le nombre d'ecritures sous charge (EF-14).</li>
 *   <li><b>{@code hourly_aggregates}</b> ({@code _id = "sensorId|yyyy-MM-ddTHH"}) et
 *       <b>{@code daily_aggregates}</b> ({@code _id = "sensorId|yyyy-MM-dd"}) : upsert avec
 *       operateurs <b>atomiques</b> cote serveur — {@code $inc} (count, sumPowerKw, energyKwh,
 *       thresholdExceededCount, anomalyCount), {@code $min}/{@code $max} (min/maxPowerKw),
 *       {@code $setOnInsert} (cles identifiantes), {@code $set} (updatedAt). Les agregats sont
 *       ADDITIFS : la moyenne se calcule a la lecture (sumPowerKw / count, D-13). Piege leve : sur
 *       un document neuf les operateurs MongoDB {@code $min}/{@code $max} prennent la valeur
 *       fournie, il n'y a pas de "0" parasite.</li>
 *   <li><b>{@code sensor_state}</b> ({@code _id = sensorId}) : {@code $set} de l'etat Welford final
 *       (n, mean, m2) et des dernieres valeurs (lastMeasuredAt, lastPowerKw, lastEnergyBasisTs)
 *       telles que reconstruites par le consumer — la monotonie des timestamps (mesures en retard)
 *       est garantie en amont, dans le consumer.</li>
 *   <li><b>{@code anomalies}</b> : insertion idempotente grace a l'index unique
 *       {@code (eventId, type)} : un {@code DuplicateKeyException} signifie "deja traite" (rejeu du
 *       lot apres panne), il est ignore et journalise en debug.</li>
 * </ul>
 *
 * <p><b>Contrat d'acquittement</b> : une exception propagee ici remonte au consumer, qui n'acquitte
 * pas le lot Redis ; celui-ci sera rejoue. Toutes les ecritures sont idempotentes (upserts par cle
 * deterministe, insert a index unique) : le rejeu est sans danger. La cle de dedup Redis n'est
 * marque qu'APRES la reussite de {@link #apply} (voir {@code AnalyticsStreamConsumer}) ; une panne
 * dans cette fenetre peut compter une mesure deux fois — bias negligeable, documente (CDC 6.5).
 */
@Component
public class AggregateWriter {

    private static final Logger log = LoggerFactory.getLogger(AggregateWriter.class);

    /** Cles des documents agregats, format UTC fige par le CDC (section 6.3). */
    private static final DateTimeFormatter HOUR_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final MongoTemplate mongo;
    private final Clock clock;

    public AggregateWriter(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Etat Welford + dernieres valeurs du capteur APRES traitement de la mesure (CDC 6.5). */
    public record Stats(long n, double mean, double m2, Instant lastMeasuredAt,
                        Double lastPowerKw, Instant lastEnergyBasisTs) {}

    /**
     * Une mesure evaluee : l'evenement, ses resultats d'analyse et l'etat du capteur qui en
     * resulte. Pour un capteur, l'etat final du lot est celui du DERNIER {@code Evaluated} (le
     * consumer trie par timestamp) : {@code statsAfter} est immuable et reflete la monotonie.
     */
    public record Evaluated(EnergyMeasurement measurement,
                            double energyIncrementKwh,
                            boolean thresholdExceeded,
                            /** Seuil effectif applique (kW), nul si aucun seuil actif. */
                            Double thresholdKw,
                            /** SENSOR ou DEFAULT ({@code SensorClient.Threshold.Source}). */
                            SensorClient.Threshold.Source thresholdSource,
                            /** Score z utilise pour la decision, null si non calcule. */
                            Double zScore,
                            List<String> anomalyTypes,
                            Stats statsAfter) {

        public Evaluated {
            anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
        }
    }

    /** Compteurs incrementaux d'une heure ou d'un jour, collectes en memoire avant l'upsert. */
    private static final class Totals {
        long count;
        double sumPowerKw;
        double minPowerKw = Double.POSITIVE_INFINITY;
        double maxPowerKw = Double.NEGATIVE_INFINITY;
        double energyKwh;
        long thresholdExceededCount;
        long anomalyCount;

        void add(Evaluated e) {
            double power = e.measurement().powerKw();
            count++;
            sumPowerKw += power;
            minPowerKw = Math.min(minPowerKw, power);
            maxPowerKw = Math.max(maxPowerKw, power);
            energyKwh += e.energyIncrementKwh();
            if (e.thresholdExceeded()) {
                thresholdExceededCount++;
            }
            anomalyCount += e.anomalyTypes().size();
        }
    }

    /** Agregats d'un capteur dans le lot : une heure UTC et un jour UTC, plus l'etat final. */
    private static final class SensorBatch {
        final String sensorId;
        final Instant hour;
        final String day;
        final Totals hourTotals = new Totals();
        final Totals dayTotals = new Totals();
        Stats lastStats;

        SensorBatch(String sensorId, Instant measurementTs) {
            this.sensorId = sensorId;
            this.hour = measurementTs.truncatedTo(ChronoUnit.HOURS);
            this.day = DAY_KEY.format(measurementTs);
        }

        void add(Evaluated e) {
            hourTotals.add(e);
            dayTotals.add(e);
            lastStats = e.statsAfter(); // ordre de tri garantit que le dernier etat est le final
        }
    }

    /**
     * Applique un lot evalue : agregats horaires et quotidiens, etat des capteurs, anomalies.
     *
     * @throws RuntimeException si une ecriture Mongo echoue : aucun ACK cote consumer, le lot sera
     *         rejoue (sans danger, toutes les ecritures sont idempotentes)
     */
    public void apply(List<Evaluated> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        Map<String, SensorBatch> perSensor = new LinkedHashMap<>();
        for (Evaluated e : batch) {
            EnergyMeasurement m = e.measurement();
            SensorBatch sb = perSensor.computeIfAbsent(
                    m.sensorId(), id -> new SensorBatch(id, m.timestamp()));
            sb.add(e);
        }
        Instant now = clock.instant();
        for (SensorBatch sb : perSensor.values()) {
            upsertAggregate(sb, now);
            upsertSensorState(sb, now);
        }
        insertAnomalies(batch, now);
    }

    /** Un upsert horaire + un upsert quotidien par capteur et par lot (D-14). */
    private void upsertAggregate(SensorBatch sb, Instant now) {
        mongo.upsert(
                byId(id(sb.sensorId, HOUR_KEY.format(sb.hour))),
                aggregateUpdate(sb.sensorId, sb.hour, null, sb.hourTotals, now),
                HourlyAggregateDocument.class);
        mongo.upsert(
                byId(id(sb.sensorId, sb.day)),
                aggregateUpdate(sb.sensorId, null, sb.day, sb.dayTotals, now),
                DailyAggregateDocument.class);
    }

    private static Update aggregateUpdate(String sensorId, Instant hour, String day,
                                          Totals t, Instant now) {
        Update update = new Update()
                .inc("count", t.count)
                .inc("sumPowerKw", t.sumPowerKw)
                .inc("energyKwh", t.energyKwh)
                .inc("thresholdExceededCount", t.thresholdExceededCount)
                .inc("anomalyCount", t.anomalyCount)
                .min("minPowerKw", t.minPowerKw)
                .max("maxPowerKw", t.maxPowerKw)
                .setOnInsert("sensorId", sensorId)
                .set("updatedAt", now);
        if (hour != null) {
            update.setOnInsert("hour", hour);
        }
        if (day != null) {
            update.setOnInsert("date", day);
        }
        return update;
    }

    /** Etat Welford et dernieres valeurs du capteur (le consumer a deja assure la monotonie). */
    private void upsertSensorState(SensorBatch sb, Instant now) {
        Stats s = sb.lastStats;
        if (s == null) {
            return;
        }
        Update update = new Update()
                .set("n", s.n())
                .set("mean", s.mean())
                .set("m2", s.m2())
                .set("lastMeasuredAt", s.lastMeasuredAt())
                .set("lastPowerKw", s.lastPowerKw())
                .set("lastEnergyBasisTs", s.lastEnergyBasisTs())
                .setOnInsert("sensorId", sb.sensorId)
                .set("updatedAt", now);
        mongo.upsert(byId(sb.sensorId), update, SensorStateDocument.class);
    }

    /**
     * Insertion des anomalies : l'index unique {@code (eventId, type)} rend l'operation
     * idempotente ; un doublon (rejeu du lot) est ignore silencieusement.
     */
    private void insertAnomalies(List<Evaluated> batch, Instant now) {
        for (Evaluated e : batch) {
            EnergyMeasurement m = e.measurement();
            for (String typeName : e.anomalyTypes()) {
                AnomalyDocument doc = new AnomalyDocument(
                        null,
                        m.sensorId(),
                        m.timestamp(),
                        m.eventId().toString(),
                        AnomalyType.valueOf(typeName),
                        severityOf(typeName),
                        m.powerKw(),
                        e.thresholdKw(),
                        e.thresholdSource() == null ? null : e.thresholdSource().name(),
                        e.zScore(),
                        now);
                try {
                    mongo.insert(doc);
                } catch (DuplicateKeyException duplicate) {
                    log.debug("Anomalie deja inseree (eventId {}, type {}), rejeu ignore",
                            doc.eventId(), typeName);
                }
            }
        }
    }

    /** Severites par defaut du CDC 6.5 : THRESHOLD = HIGH, ZSCORE = MEDIUM. */
    private static Severity severityOf(String type) {
        return switch (type) {
            case "THRESHOLD" -> Severity.HIGH;
            case "ZSCORE" -> Severity.MEDIUM;
            default -> Severity.LOW;
        };
    }

    private static String id(String sensorId, String periodKey) {
        return sensorId + "|" + periodKey;
    }

    private static Query byId(String id) {
        return new Query(Criteria.where("_id").is(id));
    }
}
