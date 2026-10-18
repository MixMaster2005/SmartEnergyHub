-- ============================================================================
-- SmartEnergy Hub - schema Cassandra (storage-service)  -  CDC section 6.1
-- Execute par le conteneur "cassandra-init" apres que Cassandra soit sain.
-- Idempotent : peut etre rejoue sans erreur.
-- Dev : replication_factor 1 (un seul noeud). Prod : NetworkTopologyStrategy, RF 3, LOCAL_QUORUM.
-- ============================================================================

CREATE KEYSPACE IF NOT EXISTS smartenergy
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- Requete principale : historique d'un capteur entre deux dates.
-- Partition (sensor_id, day) : partitions bornees (~43 200 lignes/jour a 1 mesure / 2 s).
-- Clustering measured_at DESC : les mesures les plus recentes d'abord.
-- day = date UTC de measured_at.
CREATE TABLE IF NOT EXISTS smartenergy.measurements_by_sensor_day (
  sensor_id    text,
  day          date,
  measured_at  timestamp,
  power_kw     double,
  voltage      double,
  temperature  double,
  current_a    double,
  frequency_hz double,
  state        text,
  event_id     uuid,
  PRIMARY KEY ((sensor_id, day), measured_at)
) WITH CLUSTERING ORDER BY (measured_at DESC)
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS',
                    'compaction_window_size': 1};
-- Retention (optionnelle) : ajouter  AND default_time_to_live = 31536000  (365 jours)

-- Derniere mesure par capteur (lecture O(1)).
-- ECRITURE : INSERT ... USING TIMESTAMP <measured_at en microsecondes> (decision D-09) afin
-- qu'une mesure en retard n'ecrase jamais une mesure plus recente.
CREATE TABLE IF NOT EXISTS smartenergy.latest_measurement_by_sensor (
  sensor_id    text PRIMARY KEY,
  measured_at  timestamp,
  power_kw     double,
  voltage      double,
  temperature  double,
  current_a    double,
  frequency_hz double,
  state        text,
  event_id     uuid
);
