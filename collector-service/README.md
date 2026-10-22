# collector-service (port local 8081, 8080 dans Docker)

**Rôle** : recevoir les mesures, les valider, les publier dans Redis Streams **sans bloquer l'émetteur** (EF-02, EF-03).
Aucune base propre ; ne vérifie pas l'existence du capteur (disponibilité avant cohérence).

## Contrat
`POST /api/measurements` — corps `EnergyMeasurementRequest` (schéma : `contracts/schemas/energy-measurement-request.schema.json`)

| Code | Cas |
|---|---|
| 202 | publiée dans le stream ; en-tête `X-Event-Id` (**UUID v5 déterministe** de `sensorId|timestamp`, D-07) ; corps vide |
| 400 | JSON mal formé, règle violée, trop ancienne (> 24 h), dans le futur (> 5 min) — Problem Details (`code`, `traceId`) |
| 415 | type non JSON — renvoyé par le handler global (platform-web) mais actuellement **avec un corps vide** (écart connu à corriger) |
| 413 | corps > 10 Ko — **à implémenter** (`BodySizeLimitFilter`, étape 12) ; non délivré aujourd'hui |
| 503 | Redis indisponible — `Retry-After: 5`. **Jamais de 202 sans `XADD` réussi** |

Un doublon est **accepté** (202) : il est neutralisé en aval par l'idempotence.
Les règles de validation (plages `powerKw`/`voltage`, regex `sensorId`…) vivent dans
`contracts` (`EnergyMeasurementRequest`), pas dans ce service.

## Configuration (`application.yml`)
`SERVER_PORT` (8081 en local, 8080 dans Docker) · `REDIS_HOST/PORT/PASSWORD` ·
`MEASUREMENT_MAX_AGE` (PT24H) · `MEASUREMENT_MAX_FUTURE` (PT5M) · `STREAM_NAME` ·
`STREAM_MAXLEN` · Actuator : `health,info,metrics,prometheus`, readiness `readinessState,redis`.
Jackson : **pas de coercion** (`"3.72"` en chaîne est refusé).
Métriques : `energy_measurements_published_total` + timer `energy_measurements_publish`.

## Déjà fait
Application, propriétés, bean `Clock`, `application.yml`, `/api/health` + erreurs + `X-Request-Id` (platform-web), image Docker (Dockerfile racine).
`validation/MeasurementTimeValidator` — règles temporelles 24 h / 5 min (étape 1), test unitaire `MeasurementTimeValidatorTest` (8 cas, horloge fixe).
`stream/MeasurementPublisher` — `XADD` `MAXLEN ~`, 503 si Redis KO, métriques publication (étape 3), test unitaire `MeasurementPublisherTest`.
`web/MeasurementController` — 202 + `X-Event-Id` déterministe, OpenAPI (étape 3), test unitaire `MeasurementControllerTest`.

## À faire (dans l'ordre)
1. `web/BodySizeLimitFilter` — 413 au-delà de 10 Ko (étape 12) — *fichier squelette, tout est commenté*
2. `CollectorIntegrationTest` — scénarios 1, 2, 8, 9 — *fichier squelette, tout est commenté, aucun test exécuté à ce jour*

## Pièges
- Ne **jamais** réécrire la sérialisation : `MeasurementEventCodec.toFields` est le contrat.
- `timestamp` accepté en ISO-8601 UTC (`Z`) : tester le comportement avec un décalage `+03:00` et le documenter — *ni test ni documentation à ce jour*.
- Calculer `traceId` avec `RequestIdFilter.currentRequestId()`.
- Le 415 traverse `GlobalExceptionHandler` (platform-web) : prévoir un corps Problem Details complet (cf. « Contrat »).

## Critère de validation (étape 3)
Une requête valide crée un évènement dans Redis ; une requête invalide n'en crée aucun.
*(Pas encore de test automatisé qui le prouve : `CollectorIntegrationTest` reste à implémenter.)*
