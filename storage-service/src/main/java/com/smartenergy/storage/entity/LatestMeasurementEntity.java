package com.smartenergy.storage.entity;

// ========================================================================
// TODO(etape-5) - Entite Cassandra latest_measurement_by_sensor
// ========================================================================
// Guide : etape 8 (route /latest). CDC : decision D-09, section 6.1.
// Table a cle simple (sensor_id) : une ligne par capteur = derniere mesure connue.
//
// A faire : entite @Table("latest_measurement_by_sensor"), @PrimaryKey String sensorId + memes colonnes
// que la table principale (measured_at, power_kw, ..., event_id).
//
// Ecriture : INSERT ... USING TIMESTAMP <measured_at en MICROSECONDES> (voir CassandraMeasurementWriter) pour que
// Cassandra resolve les conflits sur le temps d'EVENEMENT : une mesure en retard n'ecrase jamais une plus recente.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.springframework.data.cassandra.core.mapping.Column;
// import org.springframework.data.cassandra.core.mapping.PrimaryKey;
// import org.springframework.data.cassandra.core.mapping.Table;
//
// @Table("latest_measurement_by_sensor")
// public class LatestMeasurementEntity {
//     // @PrimaryKey("sensor_id") private String sensorId;
//     // @Column("measured_at") private java.time.Instant measuredAt;
//     // ... memes colonnes de valeurs que MeasurementByDayEntity
// }
