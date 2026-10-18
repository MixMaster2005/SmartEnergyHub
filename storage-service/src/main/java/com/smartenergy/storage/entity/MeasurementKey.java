package com.smartenergy.storage.entity;

// ========================================================================
// TODO(etape-5) - Cle primaire composite Spring Data Cassandra
// ========================================================================
// Guide : etape 5. CDC : section 6.1. Schema : scripts/init-cassandra.cql (table measurements_by_sensor_day).
//
// PRIMARY KEY ((sensor_id, day), measured_at) :
//  - sensor_id et day = cle de PARTITION (ordinal 0 et 1, type PARTITIONED) ;
//  - measured_at = cle de CLUSTERING (ordinal 2, DESCENDING).
//
// A faire : classe @PrimaryKeyClass avec @PrimaryKeyColumn(name=..., ordinal=..., type=...),
// equals/hashCode, et types Java : String, java.time.LocalDate (colonne date), java.time.Instant (timestamp).
// Expliquer dans le rapport pourquoi la partition est (sensor_id, day) et pas sensor_id seul (guide 13.1).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import java.time.Instant;
// import java.time.LocalDate;
// import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
// import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
// import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
//
// @PrimaryKeyClass
// public class MeasurementKey implements java.io.Serializable {
//     // @PrimaryKeyColumn(name = "sensor_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED) private String sensorId;
//     // @PrimaryKeyColumn(name = "day", ordinal = 1, type = PrimaryKeyType.PARTITIONED) private LocalDate day;
//     // @PrimaryKeyColumn(name = "measured_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
//     //                   ordering = org.springframework.data.cassandra.core.cql.Ordering.DESCENDING) private Instant measuredAt;
//     // constructeur, getters, equals, hashCode
// }
