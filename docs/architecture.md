# Architecture

## Flux de données

1. Le **simulateur** (ou un capteur) envoie `POST /api/measurements` au **collector-service**.
2. Le collector valide, calcule un `eventId` déterministe, publie dans le stream Redis `energy-measurements` (`XADD`)
   et répond **202**. Redis KO → **503** + `Retry-After` (jamais de 202 sans publication).
3. Deux **consumer groups** lisent le même stream, indépendamment :
   - `storage-group` → **storage-service** → Cassandra (`measurements_by_sensor_day`, `latest_measurement_by_sensor`) ;
   - `analytics-group` → **analytics-service** → MongoDB `analytics_db` (agrégats horaires/quotidiens, anomalies).
4. L'utilisateur consulte : historique (storage), capteurs (sensor), indicateurs et anomalies (analytics).
5. analytics appelle sensor-service (`GET /api/sensors/{id}`, cache Redis 60 s, circuit breaker) pour connaître le seuil d'un capteur ;
   repli : seuil par défaut.

## Propriété des données

| Service | Données |
|---|---|
| storage-service | Cassandra `smartenergy` |
| sensor-service | MongoDB `sensor_db.sensors` |
| analytics-service | MongoDB `analytics_db` : `sensor_state`, `hourly_aggregates`, `daily_aggregates`, `anomalies`, `forecasts` |
| collector-service | aucune (le stream Redis est en transit) |

Aucun service ne lit la base d'un autre.

## Fiabilité

- **At-least-once** : ACK uniquement après persistance ; messages en attente réclamés (`XAUTOCLAIM`) ; DLQ après 5 livraisons
  ou si l'évènement est illisible.
- **Idempotence** : `eventId` = UUID v5 de `sensorId|timestamp` ; clé primaire Cassandra naturelle ; dédup Redis côté analytics.
- **Redis persistant** : AOF + volume (sinon un redémarrage de Redis perd les messages en attente).
- **Disponibilité partielle** : collector indépendant de storage/analytics ; analytics indépendant de sensor-service.

## Conventions

JSON camelCase · dates ISO-8601 UTC avec `Z` · intervalles `[from, to)` · erreurs RFC 9457 (`application/problem+json`) ·
identifiant capteur `^[A-Z0-9-]{3,32}$` · chemins d'API identiques au guide (sans préfixe de version).
