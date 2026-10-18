package com.smartenergy.analytics.service;

// ========================================================================
// TODO(etape-9) - Mise a jour incrementale des agregats et insertion des anomalies (MongoDB)
// ========================================================================
// Guide : etape 6 (agregat quotidien) et etape 9. CDC : section 6.3, decisions D-13 et D-14.
//
// Objectif : appliquer un LOT de mesures (deja evaluees par AnalyticsRules) aux collections analytics_db.
//
// A faire :
//  1. Regrouper le lot par capteur, puis par heure et par jour UTC, EN MEMOIRE (micro-batch, D-14) :
//     une seule ecriture Mongo par document et par lot, pas une par mesure.
//  2. hourly_aggregates (_id = "sensorId|yyyy-MM-ddTHH") et daily_aggregates (_id = "sensorId|yyyy-MM-dd") :
//     upsert avec operateurs ATOMIQUES : $inc (count, sumPowerKw, energyKwh, thresholdExceededCount, anomalyCount),
//     $min (minPowerKw), $max (maxPowerKw), $setOnInsert (sensorId, hour/date), $set (updatedAt).
//     Avec Spring Data : MongoTemplate.upsert(query, update, collection).
//     Piege : $min / $max sur un document neuf prennent la valeur fournie (pas de "0" parasite).
//  3. sensor_state (_id = sensorId) : $set lastMeasuredAt, lastPowerKw, lastEnergyBasisTs, n, mean, m2.
//     Ne remonter lastMeasuredAt que si la mesure est plus RECENTE (retards) .
//  4. anomalies : insert avec index unique (eventId, type) ; DuplicateKeyException = deja traite -> ignorer.
//  5. Le dedup Redis (RedisKeys.dedupAnalytics) est marque APRES ces ecritures reussies (voir le consumer).
//
// Test : lot de 3 mesures dans la meme heure -> count 3, somme correcte, min/max corrects ; rejouer le meme lot
// avec dedup -> inchange.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import java.util.List;
// import org.springframework.data.mongodb.core.MongoTemplate;
// import org.springframework.stereotype.Component;
//
// @Component
// public class AggregateWriter {
//     private final MongoTemplate mongo;
//
//     public AggregateWriter(MongoTemplate mongo) {
//         this.mongo = mongo;
//     }
//
//     /** Une mesure evaluee : l'evenement + ses resultats d'analyse. */
//     public record Evaluated(EnergyMeasurement measurement, double energyIncrementKwh,
//                             boolean thresholdExceeded, java.util.List<String> anomalyTypes) {}
//
//     public void apply(List<Evaluated> batch) {
//         throw new UnsupportedOperationException("TODO etape 9");
//     }
// }
