# collector-service (port local 8081, 8080 dans Docker)

**Rôle** : recevoir les mesures, les valider, les publier dans Redis Streams **sans bloquer l'émetteur** (EF-02, EF-03).
Aucune base propre ; ne vérifie pas l'existence du capteur (disponibilité avant cohérence).

## Contrat
`POST /api/measurements` — corps `EnergyMeasurementRequest` (schéma : `contracts/schemas/energy-measurement-request.schema.json`)

| Code | Cas |
|---|---|
| 202 | publiée dans le stream ; en-tête `X-Event-Id` (**UUID v5 déterministe** de `sensorId|timestamp`, D-07) ; corps vide |
| 400 | JSON mal formé, règle violée, trop ancienne (> 24 h), dans le futur (> 5 min) — Problem Details (`code`, `traceId`) |
| 415 | type non JSON — Problem Details complet (`code` `UNSUPPORTED_MEDIA_TYPE`, `traceId`) renvoyé par le handler global (platform-web), enrichi après la classe de base |
| 413 | corps > 10 Ko (`BodySizeLimitFilter`, étape 12) — Problem Details `PAYLOAD_TOO_LARGE`, que le `Content-Length` soit annoncé ou le corps envoyé en chunked |
| 503 | Redis indisponible — `Retry-After: 5`. **Jamais de 202 sans `XADD` réussi** |

Un doublon est **accepté** (202) : il est neutralisé en aval par l'idempotence.
Les règles de validation (plages `powerKw`/`voltage`, regex `sensorId`…) vivent dans
`contracts` (`EnergyMeasurementRequest`), pas dans ce service.

## Configuration (`application.yml`)
`SERVER_PORT` (8081 en local, 8080 dans Docker) · `REDIS_HOST/PORT/PASSWORD` ·
`MEASUREMENT_MAX_AGE` (PT24H) · `MEASUREMENT_MAX_FUTURE` (PT5M) ·
`MEASUREMENT_MAX_BODY_BYTES` (10240) · `STREAM_NAME` ·
`STREAM_MAXLEN` · Actuator : `health,info,metrics,prometheus`, readiness `readinessState,redis`.
Jackson : **pas de coercion** (`"3.72"` en chaîne est refusé).
Métriques : `energy_measurements_published_total` + timer `energy_measurements_publish`.

## Déjà fait
Application, propriétés, bean `Clock`, `application.yml`, `/api/health` + erreurs + `X-Request-Id` (platform-web), image Docker (Dockerfile racine).
`validation/MeasurementTimeValidator` — règles temporelles 24 h / 5 min (étape 1), test unitaire `MeasurementTimeValidatorTest` (8 cas, horloge fixe).
`stream/MeasurementPublisher` — `XADD` `MAXLEN ~`, 503 si Redis KO, métriques publication (étape 3), test unitaire `MeasurementPublisherTest`.
`web/MeasurementController` — 202 + `X-Event-Id` déterministe, OpenAPI (étape 3), test unitaire `MeasurementControllerTest`.
`web/BodySizeLimitFilter` + `config/WebFilterConfig` — 413 au-delà de 10 Ko, que le `Content-Length`
soit annoncé (réponse écrite par le filtre) ou le corps chunked (flux compteur), filtre enregistré
juste après `RequestIdFilter` (étape 12), test unitaire `BodySizeLimitFilterTest` (5 cas).

## À faire (dans l'ordre)
1. _(rien : `CollectorIntegrationTest` est livré — scénarios 1, 2, 8 et 9, Redis en
   Testcontainers, stream confronté au schéma `measurement-event.schema.json`.)_

## Pièges
- Ne **jamais** réécrire la sérialisation : `MeasurementEventCodec.toFields` est le contrat.
- `timestamp` accepté en ISO-8601 UTC (`Z`) : tester le comportement avec un décalage `+03:00` et le documenter — *ni test ni documentation à ce jour*.
- Calculer `traceId` avec `RequestIdFilter.currentRequestId()`.
- Le 413 est écrit **par le filtre** quand le `Content-Length` dépasse la limite : un filtre
  s'exécute avant le `DispatcherServlet`, donc `GlobalExceptionHandler` est hors de sa portée.
  Le corps chunked, lui, lève une `ApiException` résolue par le handler global (mêmes champs).
- Enregistrer le filtre juste **après** `RequestIdFilter` (`HIGHEST_PRECEDENCE + 1`) : sinon la
  réponse 413 sort sans `traceId` ni `X-Request-Id`.
- Le 415 (comme le 404 et le 405) traverse `GlobalExceptionHandler` (platform-web) : le corps
  est construit par `ResponseEntityExceptionHandler` **après** `super.handleExceptionInternal`
  (les sous-methodes passent `body = null`), l'enrichissement `code`/`traceId` se fait donc
  après l'appel — un test sur `body` avant ne le verrait jamais.

## Critère de validation (étape 3)
Une requête valide crée un évènement dans Redis ; une requête invalide n'en crée aucun.
*(Prouvé par `CollectorIntegrationTest` : 202 + `X-Event-Id` + `XLEN` +1 pour une mesure
valide, 400 `application/problem+json` sans aucune publication pour l'invalide,
503 + `Retry-After: 5` quand Redis est arrêté.)*
