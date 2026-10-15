-- ============================================================
-- SmartEnergy Hub - Initialisation du schema Cassandra
-- Modele guide par les requetes (EF-08, EF-12, RG-08)
-- A executer dans le conteneur :
--   docker exec -i smartenergy-cassandra cqlsh < scripts/init-cassandra.cql
-- ============================================================

CREATE KEYSPACE IF NOT EXISTS smartenergy
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE smartenergy;

-- Mesures temporelles, partitionnees par (capteur, jour) et triees
-- par horodatage decroissant pour lire l'historique recent en premier.
-- PRIMARY KEY ((sensor_id, day), measured_at, event_id) :
--   event_id en colonne de clustering garantit l'unicite en cas
--   d'horodatages identiques et facilite l'idempotence (EF-15, RG-06).
CREATE TABLE IF NOT EXISTS measurements_by_sensor_day (
    sensor_id    text,
    day          date,
    measured_at  timestamp,
    event_id     text,
    power_kw     double,
    voltage      double,
    temperature  double,
    PRIMARY KEY ((sensor_id, day), measured_at, event_id)
) WITH CLUSTERING ORDER BY (measured_at DESC, event_id ASC);
