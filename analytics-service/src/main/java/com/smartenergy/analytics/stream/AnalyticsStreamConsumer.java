package com.smartenergy.analytics.stream;

// ========================================================================
// TODO(etape-9) - Consommateur Redis de l'analytics-group (dedup, regles, agregats)
// ========================================================================
// Guide : etape 9. CDC : section 7.5 ; scenarios 4 (pic), 8 (doublon), 11 (sensor-service arrete).
// Etend AbstractStreamConsumer (platform-stream). Traitement d'un lot :
//
//  1. Deduplication : ignorer les evenements dont la cle Redis RedisKeys.dedupAnalytics(eventId) existe.
//     Regle (CDC 6.5) : verifier AVANT, marquer APRES les ecritures Mongo reussies (TTL RedisKeys.DEDUP_TTL).
//     Une panne entre les deux peut compter une mesure deux fois : biais negligeable, a documenter.
//  2. Seuil : sensorClient.thresholdFor(sensorId) (cache + repli par defaut ; ne leve jamais d'exception).
//  3. Etat du capteur : lire sensor_state (n, mean, m2, lastEnergyBasisTs, dernier P) pour chaque capteur du lot.
//  4. Pour chaque mesure, dans l'ORDRE des timestamps :
//       - energie = AnalyticsRules.energyIncrementKwh(...) ;
//       - depassement = AnalyticsRules.isThresholdExceeded(...) -> anomalie THRESHOLD (severite HIGH) ;
//       - z = AnalyticsRules.zScore(...) -> anomalie ZSCORE si |z| > seuil ;
//       - mise a jour de Welford SEULEMENT si la mesure n'est pas anormale.
//  5. aggregateWriter.apply(lot evalue) puis marquer le dedup puis (retour) XACK par la classe de base.
//  6. Metrique energy_anomalies_detected_total{type}.
//  L'anomalie porte thresholdSource = SENSOR ou DEFAULT (voir SensorClient.Threshold.Source).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.analytics.client.SensorClient;
// import com.smartenergy.analytics.service.AggregateWriter;
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import com.smartenergy.platform.stream.AbstractStreamConsumer;
// import com.smartenergy.platform.stream.StreamProperties;
// import java.util.List;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component;
//
// @Component
// public class AnalyticsStreamConsumer extends AbstractStreamConsumer {
//     private final SensorClient sensorClient;
//     private final AggregateWriter writer;
//
//     public AnalyticsStreamConsumer(StringRedisTemplate redis, StreamProperties props,
//                                    SensorClient sensorClient, AggregateWriter writer) {
//         super(redis, props);
//         this.sensorClient = sensorClient;
//         this.writer = writer;
//     }
//
//     @Override protected void processBatch(List<EnergyMeasurement> batch) { /* dedup, regles, writer.apply, marquer dedup */ }
//     @Override protected String serviceName() { return "analytics-service"; }
// }
