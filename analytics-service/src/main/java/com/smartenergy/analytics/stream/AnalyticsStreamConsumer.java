package com.smartenergy.analytics.stream;

import com.smartenergy.analytics.client.SensorClient;
import com.smartenergy.analytics.config.AnalyticsProperties;
import com.smartenergy.analytics.model.SensorStateDocument;
import com.smartenergy.analytics.rules.AnalyticsRules;
import com.smartenergy.analytics.rules.AnalyticsRules.Welford;
import com.smartenergy.analytics.service.AggregateWriter;
import com.smartenergy.analytics.service.AggregateWriter.Evaluated;
import com.smartenergy.analytics.service.AggregateWriter.Stats;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.platform.stream.AbstractStreamConsumer;
import com.smartenergy.platform.stream.StreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consommateur Redis de l'{@code analytics-group} (CDC sections 6.5 et 7.5, decisions D-13, D-14,
 * D-20). Guide : etape 9. Scenarios couverts : 4 (pic x3 -> anomalie THRESHOLD), 8 (doublon),
 * 11 (sensor-service arrete -> seuil par defaut).
 *
 * <p>Etend {@link AbstractStreamConsumer} : boucle XREADGROUP, acquittement, reprise
 * (XPENDING + XCLAIM) et DLQ sont dans la classe mere. Le traitement d'un lot ici, dans l'ordre :
 *
 * <ol>
 *   <li><b>Deduplication</b> : les evenements dont la cle {@code RedisKeys.dedupAnalytics(eventId)}
 *       existe deja sont ignores (scenario 8, doublon). Regle CDC 6.5 : verifier AVANT, marquer
 *       APRES les ecritures Mongo reussies, avec {@code RedisKeys.DEDUP_TTL} (24 h). Une panne
 *       entre les deux peut compter une mesure deux fois : biais negligeable, documente.</li>
 *   <li><b>Seuil</b> : {@code SensorClient.thresholdFor(sensorId)} une fois par capteur et par lot
 *       (cache Redis 60 s + repli par defaut, jamais d'exception — D-20 : analytics survit a
 *       l'arret de sensor-service, scenario 11 avec {@code thresholdSource = DEFAULT}).</li>
 *   <li><b>Etat des capteurs</b> : lecture de {@code sensor_state} (n, mean, m2,
 *       lastEnergyBasisTs, dernier P) pour chaque capteur du lot, une seule fois par lot.</li>
 *   <li><b>Regles</b>, mesure par mesure dans l'ORDRE DES TIMESTAMPS :
 *       energie = {@code AnalyticsRules.energyIncrementKwh(...)} (rectangle a gauche, pas de temps
 *       plafonne) ; depassement = {@code isThresholdExceeded} -> anomalie THRESHOLD (HIGH) ;
 *       score z = {@code zScore} -> anomalie ZSCORE (MEDIUM) si {@code |z| > 3}. Les statistiques
 *       de Welford ne sont mises a jour que si la mesure N'EST PAS anormale (ne pas polluer mu et
 *       sigma avec le pic lui-meme). Les valeurs d'etat (lastMeasuredAt, lastPowerKw,
 *       lastEnergyBasisTs) ne reculent JAMAIS : une mesure en retard compte dans count/min/max
 *       mais pas dans l'energie ni dans l'etat.</li>
 *   <li><b>Agregats</b> : {@code AggregateWriter.apply(lot evalue)}, puis marquage du dedup — le
 *       tout avant l'ACK rendu par la classe mere (exception = pas d'ACK = rejeu sans danger).</li>
 *   <li><b>Metrique</b> : {@code energy_anomalies_detected_total{type}} (CDC 7.5).</li>
 * </ol>
 */
@Component
public class AnalyticsStreamConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsStreamConsumer.class);

    private static final String ANOMALIES_DETECTED = "energy_anomalies_detected_total";

    private final SensorClient sensorClient;
    private final AggregateWriter writer;
    private final MongoTemplate mongo;
    private final AnalyticsProperties props;
    private final StringRedisTemplate redisTemplate;
    private final Counter thresholdAnomalies;
    private final Counter zscoreAnomalies;

    public AnalyticsStreamConsumer(StringRedisTemplate redis, StreamProperties props,
                                   SensorClient sensorClient, AggregateWriter writer,
                                   MongoTemplate mongo, AnalyticsProperties analyticsProps,
                                   MeterRegistry registry) {
        super(redis, props);
        this.sensorClient = sensorClient;
        this.writer = writer;
        this.mongo = mongo;
        this.props = analyticsProps;
        this.redisTemplate = redis;
        this.thresholdAnomalies = registry.counter(ANOMALIES_DETECTED, "type", "THRESHOLD");
        this.zscoreAnomalies = registry.counter(ANOMALIES_DETECTED, "type", "ZSCORE");
    }

    /** Traite un lot : dedup -> seuils -> etats -> regles -> agregats -> marquage dedup. */
    @Override
    protected void processBatch(List<EnergyMeasurement> batch) {
        List<EnergyMeasurement> fresh = removeAlreadySeen(batch);
        if (fresh.isEmpty()) {
            return;
        }
        // ordre des timestamps : l'energie et la monotonie dependent de l'ordre de parcours
        fresh.sort(Comparator.comparing(EnergyMeasurement::timestamp));

        Map<String, SensorClient.Threshold> thresholds = new HashMap<>();
        Map<String, SensorStateDocument> states = loadStates(fresh);

        List<Evaluated> evaluated = new ArrayList<>(fresh.size());
        Map<String, SensorRuntime> runtimes = new HashMap<>();
        for (EnergyMeasurement m : fresh) {
            SensorRuntime runtime = runtimes.computeIfAbsent(
                    m.sensorId(), id -> SensorRuntime.from(states.get(id)));
            SensorClient.Threshold threshold = thresholds.computeIfAbsent(
                    m.sensorId(), sensorClient::thresholdFor);
            evaluated.add(evaluate(m, runtime, threshold));
        }

        writer.apply(evaluated); // echec -> exception -> pas d'ACK -> rejeu (idempotent)
        markSeen(fresh);
    }

    /** Regles 6.5 pour une mesure, dans l'ordre : energie, seuil, score z, puis etat. */
    private Evaluated evaluate(EnergyMeasurement m, SensorRuntime runtime,
                               SensorClient.Threshold threshold) {
        double energy = AnalyticsRules.energyIncrementKwh(
                runtime.lastEnergyBasisTs, runtime.lastPowerKw, m.timestamp(),
                props.expectedIntervalSeconds(), props.energyGapFactor());

        boolean exceeded = threshold.active()
                && AnalyticsRules.isThresholdExceeded(m.powerKw(), threshold.maxPowerKw());
        Double z = AnalyticsRules.zScore(runtime.welford, m.powerKw(), props.zscore().minSamples());
        boolean anomalousZ = z != null && Math.abs(z) > props.zscore().threshold();

        List<String> types = new ArrayList<>(2);
        if (exceeded) {
            types.add("THRESHOLD");
            thresholdAnomalies.increment();
        }
        if (anomalousZ) {
            types.add("ZSCORE");
            zscoreAnomalies.increment();
        }
        // mu et sigma n'integrent que les mesures normales (CDC 6.5)
        if (types.isEmpty()) {
            runtime.welford = runtime.welford.add(m.powerKw());
        }

        Instant ts = m.timestamp();
        // monotonie : une mesure en retard ne repousse ni lastMeasuredAt ni la base d'energie
        if (runtime.lastMeasuredAt == null || ts.isAfter(runtime.lastMeasuredAt)) {
            runtime.lastMeasuredAt = ts;
            runtime.lastPowerKw = m.powerKw();
            runtime.lastEnergyBasisTs = ts; // la mesure courante devient la "precedente" suivante
        }

        Stats stats = new Stats(
                runtime.welford.n(), runtime.welford.mean(), runtime.welford.m2(),
                runtime.lastMeasuredAt, runtime.lastPowerKw, runtime.lastEnergyBasisTs);
        return new Evaluated(m, energy, exceeded,
                threshold.active() ? threshold.maxPowerKw() : null, threshold.source(),
                z, types, stats);
    }

    /** Dedup : lecture des cles existantes, un seul appel MGET par lot. */
    private List<EnergyMeasurement> removeAlreadySeen(List<EnergyMeasurement> batch) {
        List<String> keys = batch.stream()
                .map(m -> RedisKeys.dedupAnalytics(m.eventId()))
                .toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            log.warn("Redis indisponible pour le dedup ({}), traitement sans filtre", e.getMessage());
            return batch; // rejeu possible -> les upserts et l'index unique absorberont le doublon
        }
        List<EnergyMeasurement> fresh = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            if (values == null || values.get(i) == null) {
                fresh.add(batch.get(i));
            } else {
                log.debug("Evenement {} deja traite (dedup), ignore", batch.get(i).eventId());
            }
        }
        return fresh;
    }

    /** Marque les evenements traites APRES ecriture Mongo reussie (CDC 6.5). */
    private void markSeen(List<EnergyMeasurement> processed) {
        for (EnergyMeasurement m : processed) {
            try {
                redisTemplate.opsForValue().set(
                        RedisKeys.dedupAnalytics(m.eventId()), "1", RedisKeys.DEDUP_TTL);
            } catch (RuntimeException e) {
                // pas bloquant : un rejeu futur sera absorbe par l'idempotence Mongo
                log.warn("Marquage dedup impossible pour {} : {}", m.eventId(), e.getMessage());
            }
        }
    }

    /** Etat Welford + dernieres valeurs, charge depuis sensor_state (une fois par capteur/lot). */
    private Map<String, SensorStateDocument> loadStates(List<EnergyMeasurement> batch) {
        Set<String> sensorIds = new HashSet<>();
        for (EnergyMeasurement m : batch) {
            sensorIds.add(m.sensorId());
        }
        Map<String, SensorStateDocument> states = new HashMap<>();
        try {
            List<SensorStateDocument> docs = mongo.find(
                    new Query(Criteria.where("_id").in(sensorIds)), SensorStateDocument.class);
            for (SensorStateDocument doc : docs) {
                states.put(doc.sensorId(), doc);
            }
        } catch (RuntimeException e) {
            // lecture KO : on repart d'un etat vide, les agregats restent corrects,
            // le z-score reprendra sa charge au prochain lot
            log.warn("Lecture de sensor_state impossible : {}", e.getMessage());
        }
        return states;
    }

    /** Etat mutable d'un capteur pendant le lot. */
    private static final class SensorRuntime {
        Welford welford = Welford.empty();
        Instant lastMeasuredAt;
        Double lastPowerKw;
        Instant lastEnergyBasisTs;

        static SensorRuntime from(SensorStateDocument doc) {
            SensorRuntime runtime = new SensorRuntime();
            if (doc != null) {
                runtime.welford = new Welford(doc.n(), doc.mean(), doc.m2());
                runtime.lastMeasuredAt = doc.lastMeasuredAt();
                runtime.lastPowerKw = doc.lastPowerKw();
                runtime.lastEnergyBasisTs = doc.lastEnergyBasisTs();
            }
            return runtime;
        }
    }

    /** Nom du service pour les journaux et le champ {@code DlqFields.SERVICE}. */
    @Override
    protected String serviceName() {
        return "analytics-service";
    }
}
