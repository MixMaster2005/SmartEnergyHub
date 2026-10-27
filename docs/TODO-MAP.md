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

Tests d'intégration **livrés** (Testcontainers, exécutés à chaque `mvn test`) :
`CollectorIntegrationTest` (6 tests, scénarios 1, 2, 8, 9),
`StorageIntegrationTest` (5 tests, scénarios 3, 5, 6, 8),
`AnalyticsIntegrationTest` (4 tests, scénarios 4, 8, 11, avec faux sensor-service HTTP).

**Total : 1 fichier à compléter.**
