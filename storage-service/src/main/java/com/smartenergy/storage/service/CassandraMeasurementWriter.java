package com.smartenergy.storage.service;

// ========================================================================
// TODO(etape-7) - Ecriture idempotente des mesures dans Cassandra
// ========================================================================
// Guide : etapes 5 et 7 (idempotence). CDC : decisions D-08, D-09, D-10 ; tests scenarios 1, 6, 8.
//
// Objectif : persister un lot de mesures dans les DEUX tables, de facon idempotente.
//
// A faire :
//  1. Pour chaque EnergyMeasurement : INSERT dans measurements_by_sensor_day
//     (rejouer le meme message ecrase la meme ligne avec les memes valeurs : idempotent par cle primaire).
//  2. INSERT dans latest_measurement_by_sensor avec USING TIMESTAMP = timestamp en microsecondes
//     (Instant -> epoch millis * 1000). Avec Spring Data : option de timestamp d'ecriture sur les WriteOptions.
//  3. Ne pas ecrire de null explicite (tombstones, D-10).
//  4. Ecritures asynchrones puis attente groupee pour le debit ; propager la premiere erreur : le lot sera retente.
//  5. Metriques : energy_persist_duration_seconds (Timer) et energy_persist_errors_total (Counter).
//
// Pas de batch Cassandra "logged" entre deux tables : deux upserts independants et idempotents suffisent.
// Tests : meme message deux fois -> une seule ligne ; mesure en retard n'ecrase pas "latest" (voir StorageIntegrationTest).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import java.util.List;
// import org.springframework.data.cassandra.core.CassandraTemplate;
// import org.springframework.stereotype.Component;
//
// @Component
// public class CassandraMeasurementWriter {
//     private final CassandraTemplate cassandra;
//
//     public CassandraMeasurementWriter(CassandraTemplate cassandra) {
//         this.cassandra = cassandra;
//     }
//
//     public void writeBatch(List<EnergyMeasurement> batch) {
//         // pour chaque mesure : upsert table principale + upsert latest (USING TIMESTAMP)
//         throw new UnsupportedOperationException("TODO etape 7");
//     }
// }
