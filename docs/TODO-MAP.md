# Carte des classes à implémenter

Généré à partir des en-têtes `TODO(...)` du code (`make todo` donne la même liste en ligne de commande).
Chaque fichier contient : le guide/CDC de référence, les étapes à suivre, les pièges, les tests attendus,
puis un **squelette commenté** à décommenter (`Ctrl+/`) et à compléter.

Ordre conseillé : de haut en bas. Les classes déjà opérationnelles (contrats, plomberie web, sensor-service,
simulateur, persistance storage des étapes 5 et 7, scripts de base de données, Docker) ne figurent pas ici.

## Étape 8 · API de consultation  _(plan : J5)_

| Fichier | À faire |
|---|---|
| `storage-service/src/main/java/com/smartenergy/storage/service/HistoryCursor.java` | Curseur de pagination opaque (jour + dernier measured_at) |
| `storage-service/src/main/java/com/smartenergy/storage/service/MeasurementQueryService.java` | Lecture de l'historique et de la derniere mesure |
| `storage-service/src/main/java/com/smartenergy/storage/web/MeasurementQueryController.java` | Routes GET /api/measurements/{sensorId} et /latest |
| `analytics-service/src/main/java/com/smartenergy/analytics/service/AnalyticsQueryService.java` | Lecture des indicateurs : summary, daily-summary, anomalies, exceedances |
| `analytics-service/src/main/java/com/smartenergy/analytics/web/AnalyticsController.java` | Routes /api/analytics/** et alias /api/sensors/{id}/daily-summary |

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
| `analytics-service/src/test/java/com/smartenergy/analytics/AnalyticsIntegrationTest.java` | Tests d'integration analytics (Testcontainers Redis + MongoDB) |

**Total : 10 fichiers à compléter.**
