# storage-service (port local 8082, 8080 dans Docker)

**Rôle** : consommer le stream (`storage-group`), persister dans **Cassandra**, servir l'historique et la dernière mesure
(EF-04, EF-06, EF-07, EF-15). **Propriétaire** du keyspace `smartenergy` ; ne touche ni MongoDB ni les autres services.

## Données (voir `scripts/init-cassandra.cql`)
- `measurements_by_sensor_day` — partition `(sensor_id, day)`, clustering `measured_at DESC`, compaction TWCS
- `latest_measurement_by_sensor` — une ligne par capteur, écrite avec `USING TIMESTAMP` = date de mesure

## Contrat REST *(étape 8 — livré)*
Routes actives sur le port 8082, documentées en OpenAPI (Swagger UI) :

| Route | Réponses |
|---|---|
| `GET /api/measurements/{sensorId}?from&to&limit&cursor&order` | 200 `MeasurementHistoryResponse` ; 400 `RANGE_TOO_LARGE` (> 7 jours) / `INVALID_PARAMETER` ; capteur sans donnée : 200, `items` vide |
| `GET /api/measurements/{sensorId}/latest` | 200 ; 404 `MEASUREMENT_NOT_FOUND` |

Plage `[from, to)` ; `limit` 100 par défaut, 1000 max ; `order` = `asc`/`desc` (défaut `desc`) ;
`nextCursor` opaque, `null` en fin de liste. Lecture de la dernière mesure : cache-aside
`cache:latest:{sensorId}` (TTL 30 s, cf. CDC 6.4). Routes à documenter en OpenAPI (EF-17).

## Configuration
`SERVER_PORT` (8082 en local, 8080 dans Docker via Compose) · `REDIS_HOST/PORT/PASSWORD` ·
`CASSANDRA_HOST/PORT/DC` (`local-datacenter` obligatoire : `datacenter1`) · `schema-action: none`
(schéma par script `cassandra-init`) · `STREAM_NAME` (défaut `energy-measurements`) ·
`STREAM_MAX_DELIVERIES` (défaut 5) · Actuator : `health,info,metrics,prometheus`,
readiness `readinessState,redis,cassandra`.

## Déjà fait
Application, `application.yml` (Redis + Cassandra), schéma CQL + conteneur
d'initialisation, healthchecks ; boucle de consommation partagée
`platform-stream/AbstractStreamConsumer` (étape 4 : XREADGROUP, ACK, reprise, DLQ) ;
entités Cassandra (étape 5 : `MeasurementKey` composite, `MeasurementByDayEntity`,
`LatestMeasurementEntity`, factories `from(EnergyMeasurement)`) ; persistance
(étape 7 : `service/CassandraMeasurementWriter` — 2 upserts idempotents, `USING
TIMESTAMP` = temps d'événement sur `latest`, écriture asynchrone du driver
(D-23), métriques `energy_persist_duration_seconds` / `energy_persist_errors_total`
; `stream/StorageStreamConsumer` — branche le writer sur la boucle partagée,
ACK uniquement après persistance) + tests (`MeasurementKeyTest`,
`CassandraMappingTest`, `CassandraMeasurementWriterTest` : rejeu, mesure en
retard, nulls omis, erreurs — Cassandra Testcontainers et application de
`scripts/init-cassandra.cql`, helpers dans `CassandraTestSupport`) ; API de
lecture (étape 8 : `service/HistoryCursor` — curseur opaque base64url
`jour|epochMillis`, jeton corrompu → 400 ;
`service/MeasurementQueryService` — historique avec **une requête Cassandra
par jour UTC** en asynchrone (`executeAsync` du pilote), fusion par
concaténation, `LIMIT limit + 1` pour `nextCursor`, plage `[from, to)` de 7 j
max, `latest` en cache-aside `cache:latest:{sensorId}` (TTL 30 s, Redis
best-effort) ;
`web/MeasurementQueryController` — `GET /api/measurements/{sensorId}` et
`/latest`, `@DateTimeFormat(ISO.DATE_TIME)`, OpenAPI ; test unitaire
`HistoryCursorTest`).

## À faire (dans l'ordre)
_(rien : `StorageIntegrationTest` est livré — scénarios 3, 5, 6 et 8 en
Testcontainers Cassandra + Redis)._

## Pièges
- **ACK après persistance** uniquement, sinon perte silencieuse.
- Écriture asynchrone (D-23) : le lot est attendu d'un seul `allOf().join()` ; la première
  erreur remonte telle quelle → pas d'ACK → **tout le lot** est rejoué (sans danger, writer
  idempotent). Ne jamais acquitter un lot partiellement écrit.
- Une requête **par jour** de la plage ; jamais de `IN` sur la clé de partition.
- Curseur d'historique : borne **stricte** uniquement sur le jour du curseur (`>` en asc,
  `<` en desc), bornes normales sur les autres jours — sinon doublons ou trous de pagination.
- Null explicite = tombstone : omettre les colonnes absentes (Spring Data les omit par défaut,
  ne pas activer `insertNulls`).
- `USING TIMESTAMP` attend des **microsecondes** : passer un `Instant` à `InsertOptions`
  (`timestamp(Instant)` convertit), jamais un `long` en millis.
- Cassandra met 1 à 2 minutes à démarrer (le Compose attend `cassandra-init`).
- Dans `application.yml`, le bloc `cassandra:` doit être au **premier niveau** :
  Spring Boot 3.5 lit `spring.cassandra.*` (l'ancien préfixe `spring.data.cassandra.*`
  n'existe plus et est **ignoré silencieusement** → le driver retombe sur
  `127.0.0.1:9042` et le service crash-loop en conteneur). Redis, lui, reste bien sous
  `data:` (`spring.data.redis.*`).

## Critère de validation (étapes 7 et 8 — satisfaits)
Le writer, le consumer et l'API de lecture sont livrés et couverts par
`CassandraMeasurementWriterTest` (rejeu → une seule ligne, mesure en retard sans effet sur
`latest`, nulls omis, métriques, erreur propagée) et `HistoryCursorTest` (aller-retour du
curseur, jetons corrompus rejetés en 400). L'intégration de bout en bout est prouvée par
`StorageIntegrationTest` (Testcontainers Cassandra + Redis) : mesure publiée dans le stream
retrouvée via `GET /api/measurements/{sensorId}` et `/latest` (scénario 3), rejeu → une seule
ligne (scénario 8), événement illisible en DLQ sans casser le flux, ancien après récent sans
effet sur `latest`, et arrêt/reprise du consumer sans perte (scénarios 5 et 6). La recette
plateforme (`scripts/e2e.sh`, scénarios 5, 6 et 7) le vérifie en conditions réelles Docker.

## Extension (hors MVP)

Gabarit du CDC §18 — **rien n'est codé**.

- **`EXT-11` — Observabilité avancée (étape 11)** — *aucun fichier*.
  Les métriques nécessaires au dashboard sont **déjà exposées** : `GET :8082/actuator/prometheus`
  (`energy_stream_pending{group}`, `energy_stream_lag{group}`, `energy_persist_duration_seconds`,
  `energy_persist_errors_total`). Il manque Prometheus, Grafana et les dashboards (côté infra).
  - Critère : ces métriques visibles dans Grafana (≥ 3 métriques CDC §9 en démo).
  - Renvois : CDC §18.2 · `docs/TODO-MAP.md` partie B · README racine §10.2.
- **`EXT-GW` — API Gateway (étapes 10/12)** — *scaffolding non codé* : les routes `GET /api/measurements/**`
  seront routées vers ce service via `:8080` (publication de `:8082` retirée).
  - Renvois : CDC §18.4 · `gateway-service/README.md`.
- **`EXT-B` — Évolutivité** — en cas de signal (`energy_stream_lag` proche de 50 % de `MAXLEN`),
  migration possible du transport vers **Kafka** : le contrat d'événement (`schemaVersion`,
  `contracts/`) reste identique (D-03).
  - Renvois : CDC §18.5 · `docs/decisions.md` D-03.
