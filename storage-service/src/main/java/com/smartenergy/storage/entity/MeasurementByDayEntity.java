package com.smartenergy.storage.entity;

// ========================================================================
// TODO(etape-5) - Entite Cassandra measurements_by_sensor_day
// ========================================================================
// Guide : etape 5 (livrable : entite Java). CDC : section 6.1.
// Colonnes : power_kw, voltage, temperature, current_a, frequency_hz, state, event_id (+ cle MeasurementKey).
//
// A faire :
//  - @Table("measurements_by_sensor_day"), @PrimaryKey sur MeasurementKey, @Column("power_kw")...
//  - Types optionnels en objets (Double, String) pour pouvoir etre null.
//  - Methode statique from(EnergyMeasurement m) : key = (m.sensorId(), m.day(), m.timestamp()).
//
// ATTENTION tombstones (decision D-10) : ne jamais ecrire un null EXPLICITE (il cree un tombstone).
// Les colonnes absentes doivent etre omises a l'insertion : verifier le comportement de insert() de votre version
// de Spring Data Cassandra (option "insertNulls") ou utiliser une INSERT construite colonne par colonne.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.springframework.data.cassandra.core.mapping.Column;
// import org.springframework.data.cassandra.core.mapping.PrimaryKey;
// import org.springframework.data.cassandra.core.mapping.Table;
//
// @Table("measurements_by_sensor_day")
// public class MeasurementByDayEntity {
//     // @PrimaryKey private MeasurementKey key;
//     // @Column("power_kw") private double powerKw;
//     // @Column("voltage") private double voltage;
//     // @Column("temperature") private Double temperature;
//     // @Column("current_a") private Double currentA;
//     // @Column("frequency_hz") private Double frequencyHz;
//     // @Column("state") private String state;
//     // @Column("event_id") private java.util.UUID eventId;
//     // public static MeasurementByDayEntity from(EnergyMeasurement m) { ... }
// }
