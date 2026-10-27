# analytics-service (port local 8083, 8080 dans Docker)

**Rôle** : transformer les mesures en information — agrégats horaires/quotidiens, dépassements de seuil, anomalies, prévision
(EF-08 à EF-13). **Propriétaire** de MongoDB `analytics_db`. Consomme le stream via **`analytics-group`**
(indépendant du stockage) — consumer, règles et agrégats livrés à l'étape 9.
Swagger UI (springdoc via platform-web) : http://localhost:8083/swagger-ui.html — les routes
`/api/analytics/**` (étape 8) y figurent avec exemples (EF-17).

## Données (`scripts/init-mongo.js`)
`sensor_state` (état + statistiques de Welford) · `hourly_aggregates` · `daily_aggregates` · `anomalies` (unique `eventId + type`) ·
`forecasts` (extension). Agrégats **additifs** : moyenne = `sumPowerKw / count`, calculée à la lecture.

## Contrat REST *(étape 8 — livré ; forecast reste en 501)*
Routes actives sur le port 8083, documentées en OpenAPI :

| Route | Notes |
|---|---|
| `GET /api/analytics/{sensorId}/summary?from&to` | granularité **horaire** (bornes alignées), max 31 jours ; 404 `NO_DATA` ; défauts `to=maintenant`, `from=to−24 h` |
| `GET /api/analytics/{sensorId}/daily-summary?date` et alias `GET /api/sensors/{id}/daily-summary` | agrégat quotidien ; défaut `date=jour UTC courant` (alias = D-17) |
| `GET /api/analytics/{sensorId}/anomalies?from&to&type&page&size` | anomalies récentes d'abord ; `type` ∈ `THRESHOLD`, `ZSCORE`, `FORECAST_RESIDUAL` |
| `GET /api/analytics/exceedances?threshold&from&to` | `threshold` **obligatoire** ; sans dates : état courant ; avec : maximum quotidien |
| `GET /api/analytics/{sensorId}/forecast?horizonHours` | **extension 9C** ; déclarée dès l'étape 8 mais toujours **501 `FORECAST_DISABLED`** (aucun code de prévision livré) |

## Règles de calcul (CDC 6.5) — *(étape 9, livrées)*
Implémentées dans `rules/AnalyticsRules` (classe **pure**, testée par `AnalyticsRulesTest` — 9 cas) :
énergie rectangle à gauche (pas de temps plafonné à 5 × 2 s, `0` si timestamp non croissant) · seuil strict `P > Pmax` ·
score z par Welford (min 30 échantillons, seuil 3, mesures anormales exclues des statistiques) ·
sévérité `THRESHOLD`=HIGH, `ZSCORE`=MEDIUM.
Métriques (CDC 7.5) : `energy_anomalies_detected_total{type}` (livrée), `energy_forecast_latency_seconds` (9C).

## Déjà fait
Application, propriétés, `Clock`, documents Mongo (records), `application.yml`, Actuator + Swagger.
Client REST vers sensor-service : `client/SensorClient` (cache Redis 60 s, clé `cache:sensor:{id}`,
**seuil par défaut 10 kW** en repli, ne lève jamais d'exception) + `client/SensorGateway`
(`GET /api/sensors/{id}` avec retry Resilience4j : 2 tentatives ; circuit breaker : fenêtre 10, seuil 50 %, ouverture 10 s).
**Étape 9** : `rules/AnalyticsRules` (règles pures) + `AnalyticsRulesTest`, `service/AggregateWriter`
(micro-batch D-14, upserts atomiques `$inc`/`$min`/`$max`/`$setOnInsert`/`$set`, anomalies idempotentes
via l'index unique `(eventId, type)`), `stream/AnalyticsStreamConsumer` (dédupe avant/après écriture,
tri par timestamp, seuil via `SensorClient`, état Welford dans `sensor_state`, métrique
`energy_anomalies_detected_total{type}`).
**Étape 8** : `service/AnalyticsQueryService` (summary aligné sur les heures pleines D-13, moyenne
recalculée `somme(sumPowerKw)/somme(count)`, `dailySummary` par `_id = "sensorId|yyyy-MM-dd"`,
`anomalies` paginé trié `timestamp desc`, `exceedances` état courant via `sensor_state` / maximum
quotidien via `daily_aggregates`) + `web/AnalyticsController` (routes + alias `/api/sensors/{id}/daily-summary`
D-17, `forecast` déclaré en 501, OpenAPI).

## Configuration (`application.yml`)
`SERVER_PORT` (8083 en local, 8080 dans Docker) · `REDIS_HOST/PORT/PASSWORD` · `MONGO_HOST/PORT` +
`MONGO_ANALYTICS_PASSWORD` · `SENSOR_SERVICE_URL` · `STREAM_NAME` · `STREAM_MAX_DELIVERIES` ·
`ANALYTICS_DEFAULT_MAX_POWER_KW` (10.0) · `ANALYTICS_ZSCORE_THRESHOLD` (3.0) ·
`ANALYTICS_FORECAST_ENABLED` (false) + `smartenergy.analytics.forecast.model-path` (`models/energy.onnx`) ·
readiness `readinessState,redis,mongo`. Dédup Redis : clé `dedup:analytics:{eventId}`, TTL 24 h (vérifiée avant
traitement, marquée après écriture Mongo réussie).

## À faire (dans l'ordre)
1. _(rien : `AnalyticsIntegrationTest` est livré — scénarios 4, 8 et 11, Redis + MongoDB en
   Testcontainers et un faux sensor-service HTTP embarqué pour le scénario 11.)_
2. *(extension)* `forecast/ForecastService` + `ml/` — désactivé par défaut (`forecast.enabled=false`, 501 `FORECAST_DISABLED`)

## Pièges
- Traiter les mesures d'un lot **dans l'ordre des timestamps**.
- `summary` : moyenne = somme des sommes / somme des comptes (pas la moyenne des moyennes).
- Tant que le consumer (étape 9) n'a **pas encore écrit** les agrégats d'une période (aucune mesure
  reçue depuis le démarrage), `summary`/`daily-summary`/`exceedances?from` renvoient **404 `NO_DATA`** :
  c'est le comportement attendu, pas une erreur serveur.
- Mesures très en retard → énergie légèrement faussée (recalcul nocturne = amélioration possible).
- Un sinus gonfle l'écart-type : documenter la limite du z-score.
- Dédup : vérifier avant, marquer **après** les écritures Mongo ; une panne entre les deux peut compter une
  mesure deux fois (biais négligeable, documenté) — l'index unique `(eventId, type)` protège les anomalies.
- **Première mesure d'un capteur** : l'état `sensor_state` est absent, `lastPowerKw` est `null`.
  Le consumer neutralise l'incrément d'énergie (plutôt que de déboiter un `double` sur `null`,
  ce qui rejetait tout le lot en rejeu infini puis en DLQ — bug trouvé par la recette e2e).
- **Doublon intra-lot** : deux envois rapprochés du même évènement arrivent dans le même lot,
  *avant* le marquage du dedup Redis ; le consumer ne garde qu'une occurrence par `eventId`,
  sinon les compteurs seraient incrémentés deux fois (scénario 8).
