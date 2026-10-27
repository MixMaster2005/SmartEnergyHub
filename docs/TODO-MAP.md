# Carte des classes à implémenter

Généré à partir des en-têtes `TODO(...)` du code (`make todo` donne la même liste en ligne de commande).
Chaque fichier contient : le guide/CDC de référence, les étapes à suivre, les pièges, les tests attendus,
puis un **squelette commenté** à décommenter (`Ctrl+/`) et à compléter.

Ordre conseillé : de haut en bas. Les classes déjà opérationnelles (contrats, plomberie web, sensor-service,
simulateur, persistance storage des étapes 5 et 7, API de consultation de l'étape 8, limite de taille du
corps de l'étape 12, scripts de base de données, Docker) ne figurent pas ici.

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

**Total : 4 fichiers à compléter.**
