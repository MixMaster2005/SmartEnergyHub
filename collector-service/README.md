# collector-service (port 8081)

**Rôle** : recevoir les mesures, les valider, les publier dans Redis Streams **sans bloquer l'émetteur** (EF-02, EF-03).
Aucune base propre ; ne vérifie pas l'existence du capteur (disponibilité avant cohérence).

## Contrat
`POST /api/measurements` — corps `EnergyMeasurementRequest` (schéma : `contracts/schemas/energy-measurement-request.schema.json`)

| Code | Cas |
|---|---|
| 202 | publiée dans le stream ; en-tête `X-Event-Id` ; corps vide |
| 400 | JSON mal formé, règle violée, trop ancienne (> 24 h), dans le futur (> 5 min) — Problem Details |
| 413 / 415 | corps > 10 Ko / type non JSON |
| 503 | Redis indisponible — `Retry-After: 5`. **Jamais de 202 sans `XADD` réussi** |

Un doublon est **accepté** (202) : il est neutralisé en aval par l'idempotence.

## Configuration (`application.yml`)
`REDIS_HOST/PORT/PASSWORD` · `MEASUREMENT_MAX_AGE` (PT24H) · `MEASUREMENT_MAX_FUTURE` (PT5M) · `STREAM_NAME` · `STREAM_MAXLEN`.
Jackson : **pas de coercion** (`"3.72"` en chaîne est refusé).

## Déjà fait
Application, propriétés, bean `Clock`, `application.yml`, `/api/health` + erreurs + `X-Request-Id` (platform-web), Dockerfile/Compose.

## À faire (dans l'ordre)
1. `validation/MeasurementTimeValidator` — règles temporelles (étape 1)
2. `stream/MeasurementPublisher` — `XADD` avec `MAXLEN ~`, 503 si Redis KO (étape 3)
3. `web/MeasurementController` — 202 + `X-Event-Id` (étape 3)
4. `web/BodySizeLimitFilter` — 413 (étape 12)
5. `CollectorIntegrationTest` — scénarios 1, 2, 8, 9

## Pièges
- Ne **jamais** réécrire la sérialisation : `MeasurementEventCodec.toFields` est le contrat.
- `timestamp` accepté en ISO-8601 UTC (`Z`) : tester le comportement avec un décalage `+03:00` et le documenter.
- Calculer `traceId` avec `RequestIdFilter.currentRequestId()`.

## Critère de validation (étape 3)
Une requête valide crée un évènement dans Redis ; une requête invalide n'en crée aucun.
