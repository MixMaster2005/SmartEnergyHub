# Carte des classes à implémenter

Généré à partir des en-têtes `TODO(...)` du code (`make todo` donne la même liste en ligne de commande).
Chaque fichier contient : le guide/CDC de référence, les étapes à suivre, les pièges, les tests attendus,
puis un **squelette commenté** à décommenter (`Ctrl+/`) et à compléter.

Ordre conseillé : de haut en bas. Les classes déjà opérationnelles (contrats, plomberie web, sensor-service,
simulateur, scripts de base de données, Docker) ne figurent pas ici.

## Étape 1 · Contrat de données  _(plan : J2)_

| Fichier | À faire |
|---|---|
| `collector-service/src/main/java/com/smartenergy/collector/validation/MeasurementTimeValidator.java` | Regles temporelles : mesure trop ancienne ou dans le futur |

## Étape 3 · Collector Service  _(plan : J2)_

| Fichier | À faire |
|---|---|
| `collector-service/src/main/java/com/smartenergy/collector/stream/MeasurementPublisher.java` | Publication des mesures dans Redis Streams (XADD) |
| `collector-service/src/main/java/com/smartenergy/collector/web/MeasurementController.java` | Endpoint POST /api/measurements (202 / 400 / 503) |

## Étape 4 · Redis Streams  _(plan : J3)_

| Fichier | À faire |
|---|---|
| `platform/platform-stream/src/main/java/com/smartenergy/platform/stream/AbstractStreamConsumer.java` | Boucle de consommation Redis Streams (consumer group, ACK, reprise, DLQ) |

## Étape 5 · Cassandra  _(plan : J4)_

| Fichier | À faire |
|---|---|
| `storage-service/src/main/java/com/smartenergy/storage/entity/MeasurementKey.java` | Cle primaire composite Spring Data Cassandra |
| `storage-service/src/main/java/com/smartenergy/storage/entity/MeasurementByDayEntity.java` | Entite Cassandra measurements_by_sensor_day |
| `storage-service/src/main/java/com/smartenergy/storage/entity/LatestMeasurementEntity.java` | Entite Cassandra latest_measurement_by_sensor |

## Étape 7 · Storage Service  _(plan : J3-J4)_

| Fichier | À faire |
|---|---|
| `storage-service/src/main/java/com/smartenergy/storage/service/CassandraMeasurementWriter.java` | Ecriture idempotente des mesures dans Cassandra |
| `storage-service/src/main/java/com/smartenergy/storage/stream/StorageStreamConsumer.java` | Consommateur Redis du storage-group |

## Étape 8 · API de consultation  _(plan : J5)_

| Fichier | À faire |
|---|---|
| `storage-service/src/main/java/com/smartenergy/storage/service/HistoryCursor.java` | Curseur de pagination opaque (jour + dernier measured_at) |
| `storage-service/src/main/java/com/smartenergy/storage/service/MeasurementQueryService.java` | Lecture de l'historique et de la derniere mesure |
| `storage-service/src/main/java/com/smartenergy/storage/web/MeasurementQueryController.java` | Routes GET /api/measurements/{sensorId} et /latest |
| `analytics-service/src/main/java/com/smartenergy/analytics/service/AnalyticsQueryService.java` | Lecture des indicateurs : summary, daily-summary, anomalies, exceedances |
| `analytics-service/src/main/java/com/smartenergy/analytics/web/AnalyticsController.java` | Routes /api/analytics/** et alias /api/sensors/{id}/daily-summary |

## Étape 9 · Analytics (niveaux A et B)  _(plan : J6)_

| Fichier | À faire |
|---|---|
| `analytics-service/src/main/java/com/smartenergy/analytics/rules/AnalyticsRules.java` | Regles de calcul pures : energie, seuil, score z (Welford) |
| `analytics-service/src/main/java/com/smartenergy/analytics/service/AggregateWriter.java` | Mise a jour incrementale des agregats et insertion des anomalies (MongoDB) |
| `analytics-service/src/main/java/com/smartenergy/analytics/stream/AnalyticsStreamConsumer.java` | Consommateur Redis de l'analytics-group (dedup, regles, agregats) |

## Étape 11 · Observabilité  _(plan : J7-J8)_

| Fichier | À faire |
|---|---|
| `platform/platform-stream/src/main/java/com/smartenergy/platform/stream/StreamLagMetrics.java` | Metriques Micrometer du stream (pending, lag) |

## Étape 12 · Sécurité  _(plan : J8)_

| Fichier | À faire |
|---|---|
| `collector-service/src/main/java/com/smartenergy/collector/web/BodySizeLimitFilter.java` | Limite de taille du corps (10 Ko) -> 413 |

## Étape 9C · Prévision IA (extension)  _(plan : J8)_

| Fichier | À faire |
|---|---|
| `analytics-service/src/main/java/com/smartenergy/analytics/forecast/ForecastService.java` | [EXTENSION] Prevision : baseline puis modele ONNX |

## Tests d'intégration et unitaires  _(plan : au fil de l'eau)_

| Fichier | À faire |
|---|---|
| `collector-service/src/test/java/com/smartenergy/collector/CollectorIntegrationTest.java` | Test d'integration du collector (Testcontainers Redis) |
| `storage-service/src/test/java/com/smartenergy/storage/StorageIntegrationTest.java` | Tests d'integration du storage (Testcontainers Redis + Cassandra) |
| `analytics-service/src/test/java/com/smartenergy/analytics/rules/AnalyticsRulesTest.java` | Tests unitaires des regles analytiques |
| `analytics-service/src/test/java/com/smartenergy/analytics/AnalyticsIntegrationTest.java` | Tests d'integration analytics (Testcontainers Redis + MongoDB) |

**Total : 24 fichiers à compléter.**
