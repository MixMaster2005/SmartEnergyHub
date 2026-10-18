package com.smartenergy.analytics.service;

// ========================================================================
// TODO(etape-8) - Lecture des indicateurs : summary, daily-summary, anomalies, exceedances
// ========================================================================
// Guide : etapes 8 et 9. CDC : section 5.4 (analytics-service), decision D-13 (granularite horaire).
//
// summary(sensorId, from, to) :
//  - Defaut : to = maintenant, from = to - 24 h. Plage max 31 jours (sinon RANGE_TOO_LARGE).
//  - Aligner [from, to) sur les heures pleines (effectiveFrom = from tronque a l'heure, effectiveTo = to arrondi a l'heure
//    superieure) ; lire hourly_aggregates (index sensorId + hour).
//  - count = somme ; avg = somme(sumPowerKw) / somme(count) (PAS la moyenne des moyennes) ; min = min des min ;
//    max = max des max ; energyKwh = somme ; thresholdExceededCount, anomalyCount = sommes.
//  - thresholdKw = seuil courant du capteur (SensorClient) ; aucune donnee -> ApiException(NO_DATA) (404).
//
// dailySummary(sensorId, date) : lire daily_aggregates (_id = "sensorId|yyyy-MM-dd") ; defaut = jour UTC courant ; 404 NO_DATA.
//
// anomalies(sensorId, from, to, type, page, size) : requete sur anomalies (index sensorId + timestamp desc), pagination.
//
// exceedances(threshold, from?, to?) :
//  - sans from/to : sensor_state ou lastPowerKw > threshold (index ix_lastPowerKw) ;
//  - avec from/to : daily_aggregates ou maxPowerKw > threshold sur la periode (granularite quotidienne).
//
// Test croise (critere de validation) : le count de summary doit egaler le nombre de mesures Cassandra de la meme plage.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.analytics.*;
// import com.smartenergy.contracts.common.PageResponse;
// import java.time.Instant;
// import java.time.LocalDate;
// import java.util.List;
// import org.springframework.data.mongodb.core.MongoTemplate;
// import org.springframework.stereotype.Service;
//
// @Service
// public class AnalyticsQueryService {
//     private final MongoTemplate mongo;
//
//     public AnalyticsQueryService(MongoTemplate mongo) {
//         this.mongo = mongo;
//     }
//
//     public SummaryResponse summary(String sensorId, Instant from, Instant to) { throw new UnsupportedOperationException(); }
//     public DailySummaryResponse dailySummary(String sensorId, LocalDate date) { throw new UnsupportedOperationException(); }
//     public PageResponse<AnomalyResponse> anomalies(String sensorId, Instant from, Instant to, AnomalyType type, int page, int size) { throw new UnsupportedOperationException(); }
//     public List<ExceedanceResponse> exceedances(double threshold, Instant from, Instant to) { throw new UnsupportedOperationException(); }
// }
