package com.smartenergy.storage.service;

// ========================================================================
// TODO(etape-8) - Lecture de l'historique et de la derniere mesure
// ========================================================================
// Guide : etapes 5 et 8. CDC : section 5.4 et decisions D-08, D-09.
//
// history(sensorId, from, to, limit, cursor, order) :
//  1. Valeurs par defaut : to = maintenant, from = to - 24 h. Plage [from, to) : from inclus, to exclu.
//  2. to <= from, limit hors [1,1000] -> ApiException(INVALID_PARAMETER) ; plage > 7 jours -> RANGE_TOO_LARGE.
//  3. Lister les jours UTC couverts par la plage.
//  4. UNE requete par jour : WHERE sensor_id=? AND day=? AND measured_at >= ? AND measured_at < ?
//     (jamais de IN sur la cle de partition), en ordre desc (ou asc), LIMIT = limit + 1.
//  5. Fusionner dans l'ordre, couper a "limit", calculer nextCursor (HistoryCursor) s'il reste des lignes.
//  6. Convertir en MeasurementItem (champs absents omis).
//  Un capteur sans donnee : 200 avec items vide (le capteur n'est pas verifie dans sensor-service : pas de couplage).
//
// latest(sensorId) : lire latest_measurement_by_sensor ; absent -> ApiException(MEASUREMENT_NOT_FOUND) (404).
// Option : cache-aside Redis cache:latest:{sensorId} (RedisKeys.cacheLatest, TTL 30 s).
//
// Mesurer le temps de reponse sur un jeu de donnees plus important (livrable etape 5) : le noter dans docs/tests.md.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
// import com.smartenergy.contracts.measurement.MeasurementItem;
// import java.time.Instant;
// import org.springframework.data.cassandra.core.CassandraTemplate;
// import org.springframework.stereotype.Service;
//
// @Service
// public class MeasurementQueryService {
//     private final CassandraTemplate cassandra;
//
//     public MeasurementQueryService(CassandraTemplate cassandra) {
//         this.cassandra = cassandra;
//     }
//
//     public MeasurementHistoryResponse history(String sensorId, Instant from, Instant to,
//                                               Integer limit, String cursor, String order) {
//         throw new UnsupportedOperationException("TODO etape 8");
//     }
//
//     public MeasurementItem latest(String sensorId) {
//         throw new UnsupportedOperationException("TODO etape 8");
//     }
// }
