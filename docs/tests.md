# Preuves de tests (matrice de recette)

Recette **entièrement automatisée** : `make e2e` (`scripts/e2e.sh`) exécute les scénarios 1 à 11
du CDC **plus** le contrôle **413 au-delà de 10 Ko** (étape 12), sur la plateforme Docker réelle.
Les tests d'intégration des quatre services (`mvn -B test`) couvrent les mêmes scénarios en
Testcontainers. Campagne complète rapportée dans
`preuves/tests/resultats-2026-10-28.md`.

> **Numérotations** : cette matrice utilise les scénarios **1–11** (CDC) ; le gabarit de
> preuves `preuves/tests/resultats-modele.md` utilise les labels **T01–T14** du guide.
> Les deux référent le même recette.

## Tests unitaires présents dans le dépôt (76 tests actifs)

| Classe | Service | Cas |
|---|---|---|
| `MeasurementTimeValidatorTest` | collector | 8 (bornes 24 h / 5 min, horloge fixe) |
| `MeasurementPublisherTest` | collector | 3 (XADD, MAXLEN, 503 Redis KO) |
| `MeasurementControllerTest` | collector | 4 (202 + X-Event-Id, 400, 503) |
| `BodySizeLimitFilterTest` | collector | 5 (413 `Content-Length` + corps problem+json, limite exacte 10 240 octets, chunked débordant → 413, chunked dans la limite lu intégralement, autres routes/méthodes ignorées, étape 12) |
| `SensorServiceTest` | sensor | 7 (création, doublon, sensorId incohérent, inconnu, page, version périmée → 409 `VERSION_CONFLICT` (version courante acceptée), `DuplicateKeyException` → 409 `SENSOR_ALREADY_EXISTS`) |
| `EventIdsTest` | contracts | 4 (UUID v5 déterministe) |
| `MeasurementEventCodecTest` | contracts | 7 (aller-retour du codec d'événement) |
| `MeasurementKeyTest` | storage | 3 (clés primaires composites, égalité) |
| `CassandraMappingTest` | storage | 5 (mapping entités sur Cassandra Testcontainers, D-08 à D-10) |
| `CassandraMeasurementWriterTest` | storage | 4 (rejeu → une seule ligne, `USING TIMESTAMP` sur `latest`, nulls omis, erreur propagée + métriques, D-09 à D-10, D-23) |
| `HistoryCursorTest` | storage | 8 (aller-retour du curseur base64url, jeton non URL-safe, collisions, jetons corrompus → 400 `INVALID_PARAMETER`, étape 8) |
| `AnalyticsRulesTest` | analytics | 9 (énergie rectangle tronquée, seuil strict, Welford, score z, limites documentées) |
| `StreamLagMetricsTest` | platform | 4 (noms de métriques + tag `group`, refresh pending/lag, tolérance d'une panne Redis, lag inconnu/groupe absent = 0, D-24) |
| `GlobalExceptionHandlerTest` | platform | 5 (404 / 405 / 415 enrichis `code` + `traceId` + URI `type` + titre FR, corps déjà signé non écrasé, chemin `ApiException` inchangé) |

**Total : 76 tests unitaires.**

## Tests d'intégration (18 tests, Testcontainers — exécutés à chaque `mvn -B test`)

| Classe | Service | Scénarios | Cas |
|---|---|---|---|
| `CollectorIntegrationTest` | collector | 1, 2, 8, 9 | 6 (202 + `X-Event-Id` + `XLEN` +1 et contenu du stream conformes au schéma `measurement-event.schema.json`, `sensorId` absent → 400 `VALIDATION_ERROR` sans publication, puissance en chaîne → 400 `MALFORMED_JSON`, mesure trop ancienne → 400, doublon → 202 avec même `eventId`, Redis arrêté → 503 + `Retry-After: 5`) |
| `StorageIntegrationTest` | storage | 3, 5, 6, 8 | 5 (événement du stream retrouvé via `GET /api/measurements/{id}` et `/latest` + historique, rejeu → une seule ligne, événement illisible → DLQ sans casser le flux, ancien après récent sans effet sur `latest`, arrêt/reprise du consumer sans perte) |
| `AnalyticsIntegrationTest` | analytics | 4, 8, 11 | 4 (agrégats horaires exacts avec seuil lu chez le faux sensor-service, pic ×3 → anomalie `THRESHOLD` `HIGH` listée, doublon → compteur inchangé, sensor-service coupé → seuil `DEFAULT` ; faux sensor-service HTTP JDK embarqué, D-20) |
| `SensorServiceIntegrationTest` | sensor | étape 6 | 3 (création 201 + `Location` puis lecture directe et filtre `building`, métadonnées consultables avec Cassandra arrêté — aucun conteneur Cassandra ni driver `CqlSession` au classpath, doublon → 409 `SENSOR_ALREADY_EXISTS` `application/problem+json` avec index `uk_sensorId` présent et insertion brute en double refusée par MongoDB) |

**Total : 76 + 18 = 94 tests** (`mvn -B test`).

## Scénarios de recette — résultats (campagne du 2026-10-28, plateforme Docker réelle)

`scripts/e2e.sh` : **64 vérifications, 64 OK** (scénarios 1–11 + 413 étage 12), sur plateforme
remise à plat (`docker compose down -v && up -d`). Logs : `/tmp/opencode/e2e-run2.log`.

| N° | Scénario | Résultat attendu | Preuve | Résultat obtenu |
|---|---|---|---|---|
| 1 | Mesure valide | HTTP 202 et entrée dans le stream | réponse + `XLEN` | **OK** — 202, `XLEN` +1, dernier évènement porte `SENSOR-001` (e2e + `CollectorIntegrationTest`) |
| 2 | Mesure sans `sensorId` | HTTP 400 Problem Details, aucune entrée | réponse + `XLEN` inchangé | **OK** — 400 `application/problem+json`, `code=VALIDATION_ERROR`, `XLEN` inchangé |
| 3 | Historique d'un capteur | Liste ordonnée, paginée, bornée par `from`/`to` | appel API | **OK** — historique 200 avec mesures, `/latest` 200, `limit=0` → 400 `INVALID_PARAMETER` |
| 4 | Injection d'un pic ×3 | Anomalie `THRESHOLD` listée | `/anomalies` | **OK** — anomalie listée, `thresholdKw=10.0`, `thresholdSource=SENSOR`, `severity=HIGH`, `powerKw=30.0` |
| 5 | Arrêt de storage-service pendant l'envoi | Messages conservés dans Redis (pending/lag en hausse) | `XINFO GROUPS` + `/actuator/metrics/energy_stream_pending` | **OK** — lag `storage-group` ≥ 10 pendant l'arrêt, analytics continue de traiter (groupes indépendants) |
| 6 | Redémarrage de storage-service | Reprise, aucune mesure perdue (comptage Cassandra = mesures acceptées) | comptage | **OK** — lag et pending revenus à 0, comptage Cassandra = 10 mesures acceptées, historique 200 |
| 7 | Redémarrage complet de la plateforme | Données toujours présentes (volumes) | appels API | **OK** — readiness ×4 à 200, comptage inchangé, historique et summary analytics 200 après `down -v`→`up` |
| 8 | Doublon | Une seule ligne Cassandra, compteur analytics inchangé | API + agrégat | **OK** — 2×202, **une seule** ligne Cassandra, `count` analytics inchangé = 1 |
| 9 | Redis arrêté | Collector répond 503 + `Retry-After` | réponse | **OK** — 503, `Retry-After: 5`, `code=BROKER_UNAVAILABLE`, Redis de nouveau disponible après redémarrage |
| 10 | Message poison dans le stream | Envoyé en DLQ, le flux continue | `XLEN` de la DLQ | **OK** — DLQ ≥ 1, mesure suivante acceptée 202 **et** persistée en Cassandra |
| 11 | sensor-service arrêté | Analytics traite avec le seuil par défaut | anomalie `thresholdSource = DEFAULT` | **OK** — anomalie détectée après coupure, `thresholdKw=10.0`, `thresholdSource=DEFAULT`, service restauré à 200 |
| 12 | Corps > 10 Ko (étape 12) | 413 `PAYLOAD_TOO_LARGE` sans publication | réponse + `XLEN` | **OK** — 413 `application/problem+json`, `code=PAYLOAD_TOO_LARGE`, `XLEN` inchangé |

## Mesures de performance (étapes 5 et 11) — `scripts/perf.sh` / `make perf`

Plateforme Docker réelle, mesures du 2026-10-28 (`/tmp/opencode/perf-final.log`).
Amorçage : 1 100 mesures `SENSOR-PERF` persistées en Cassandra avant mesures.

| Mesure | Méthode | Résultat | Commentaire |
|---|---|---|---|
| Lecture d'un jour d'historique (`limit` 1000) | 50 × `GET /api/measurements/SENSOR-PERF?limit=1000`, temps total par requête | **p95 47.3 ms, médiane 29.7 ms** | cible p95 < 300 ms — **PASS** |
| Débit soutenu du collector | 1 000 POST **séquentiels sur une connexion keep-alive** (fichier de config curl, une seule connexion TCP réutilisée) | **300.5 mesures/s** (1 000 POST en 3.33 s) | cible ≥ 200 mesures/s — **PASS**. Sans réutilisation de connexion (1 processus curl par requête) le mesurage tombe à ~68/s : artefact de banc, pas de la plateforme. |
| Retard de bout en bout (réception → Cassandra) | 30 essais : POST puis polling de `GET /api/measurements/{capteur}?limit=1` (lecture Cassandra directe, **hors cache `/latest` TTL 30 s**) jusqu'à apparition du timestamp posté ; capteur dédié `SENSOR-PERF-E2E`, timestamps uniques croissants (un `eventId` par essai) | **p95 163.0 ms, médiane 116.0 ms (30/30)** | cible p95 < 5 s — **PASS** |

## Checklist avant remise

Reprendre la checklist du guide (section 28) : compilation sans modification manuelle, README clair, Docker Compose, simulateur,
requêtes invalides rejetées, messages visibles dans Redis, mesures dans Cassandra, capteurs/agrégats dans MongoDB, historique via API,
résultats analytiques vérifiables, scénarios de panne testés, secrets absents du dépôt, Swagger accessible.

- Compilation : `mvn -B test` vert (94 tests) ; `docker compose build` sans erreur.
- Requêtes invalides rejetées : scénarios 2, 413, tests d'intégration (400/413 `application/problem+json`).
- Messages dans Redis / mesures dans Cassandra / agrégats MongoDB : scénarios 1, 3, 4, 8, 10, 11.
- Scénarios de panne : 5, 6, 7, 9, 11 (tout OK).
- Swagger : `/v3/api-docs` → 200 sur les 4 services.
