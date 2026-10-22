# analytics-service (port local 8083, 8080 dans Docker)

**Rôle** : transformer les mesures en information — agrégats horaires/quotidiens, dépassements de seuil, anomalies, prévision
(EF-08 à EF-13). **Propriétaire** de MongoDB `analytics_db`. Doit consommer le stream via **`analytics-group`**
(indépendant du stockage) — *le consommateur reste à implémenter (étape 9) ; seul le groupe est configuré aujourd'hui*.
Swagger UI (springdoc via platform-web) : http://localhost:8083/swagger-ui.html — aucune route analytics n'y figure tant que
l'étape 8 n'est pas livrée (EF-17 : documenter toutes les routes en OpenAPI avec exemples).

## Données (`scripts/init-mongo.js`)
`sensor_state` (état + statistiques de Welford) · `hourly_aggregates` · `daily_aggregates` · `anomalies` (unique `eventId + type`) ·
`forecasts` (extension). Agrégats **additifs** : moyenne = `sumPowerKw / count`, calculée à la lecture.

## Contrat REST *(à implémenter — étapes 8 et 9C)*
Aucune route `/api/analytics/**` n'est encore câblée ; contrat cible :

| Route | Notes |
|---|---|
| `GET /api/analytics/{sensorId}/summary?from&to` | granularité **horaire** (bornes alignées), max 31 jours ; 404 `NO_DATA` ; défauts `to=maintenant`, `from=to−24 h` |
| `GET /api/analytics/{sensorId}/daily-summary?date` et alias `GET /api/sensors/{id}/daily-summary` | agrégat quotidien ; défaut `date=jour UTC courant` (alias = D-17) |
| `GET /api/analytics/{sensorId}/anomalies?from&to&type&page&size` | anomalies récentes d'abord ; `type` ∈ `THRESHOLD`, `ZSCORE`, `FORECAST_RESIDUAL` |
| `GET /api/analytics/exceedances?threshold&from&to` | `threshold` **obligatoire** ; sans dates : état courant ; avec : maximum quotidien |
| `GET /api/analytics/{sensorId}/forecast?horizonHours` | **extension** ; défaut 6 h, plage 1–24 ; 501 `FORECAST_DISABLED` si inactif |

## Règles de calcul (CDC 6.5) — *à implémenter dans* `rules/AnalyticsRules`
Énergie (rectangle à gauche, pas de temps plafonné à 5 × 2 s) · seuil strict `P > Pmax` · score z par Welford (min 30 échantillons, seuil 3,
mesures anormales exclues des statistiques) · sévérité `THRESHOLD`=HIGH, `ZSCORE`=MEDIUM.
Métriques attendues (CDC 7.5) : `energy_anomalies_detected_total{type}`, `energy_forecast_latency_seconds`.

## Déjà fait
Application, propriétés, `Clock`, documents Mongo (records), `application.yml`, Actuator + Swagger.
Client REST vers sensor-service : `client/SensorClient` (cache Redis 60 s, clé `cache:sensor:{id}`,
**seuil par défaut 10 kW** en repli, ne lève jamais d'exception) + `client/SensorGateway`
(`GET /api/sensors/{id}` avec retry Resilience4j : 2 tentatives ; circuit breaker : fenêtre 10, seuil 50 %, ouverture 10 s).

## Configuration (`application.yml`)
`SERVER_PORT` (8083 en local, 8080 dans Docker) · `REDIS_HOST/PORT/PASSWORD` · `MONGO_HOST/PORT` +
`MONGO_ANALYTICS_PASSWORD` · `SENSOR_SERVICE_URL` · `STREAM_NAME` · `STREAM_MAX_DELIVERIES` ·
`ANALYTICS_DEFAULT_MAX_POWER_KW` (10.0) · `ANALYTICS_ZSCORE_THRESHOLD` (3.0) ·
`ANALYTICS_FORECAST_ENABLED` (false) + `smartenergy.analytics.forecast.model-path` (`models/energy.onnx`) ·
readiness `readinessState,redis,mongo`. Dédup Redis : clé `dedup:analytics:{eventId}`, TTL 24 h (à écrire, étape 9).

## À faire (dans l'ordre)
1. `rules/AnalyticsRules` + `AnalyticsRulesTest` — **commencer ici** (fonctions pures, jeu de test de référence dans l'en-tête)
2. `service/AggregateWriter` — upserts atomiques (`$inc`, `$min`, `$max`, `$setOnInsert`), anomalies idempotentes
3. `stream/AnalyticsStreamConsumer` — dédup (vérifier avant, marquer après), règles, agrégats
4. `service/AnalyticsQueryService`, `web/AnalyticsController` (étape 8) — annoter en OpenAPI
5. `AnalyticsIntegrationTest` — scénarios 4, 8, 11
6. *(extension)* `forecast/ForecastService` + `ml/` — désactivé par défaut (`forecast.enabled=false`, 501 `FORECAST_DISABLED`)

## Pièges
- Traiter les mesures d'un lot **dans l'ordre des timestamps**.
- `summary` : moyenne = somme des sommes / somme des comptes (pas la moyenne des moyennes).
- Limite connue : mesures très en retard → énergie légèrement faussée (recalcul nocturne = amélioration possible).
- Un sinus gonfle l'écart-type : documenter la limite du z-score.
