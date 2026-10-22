# storage-service (port local 8082, 8080 dans Docker)

**Rôle** : consommer le stream (`storage-group`), persister dans **Cassandra**, servir l'historique et la dernière mesure
(EF-04, EF-06, EF-07, EF-15). **Propriétaire** du keyspace `smartenergy` ; ne touche ni MongoDB ni les autres services.

## Données (voir `scripts/init-cassandra.cql`)
- `measurements_by_sensor_day` — partition `(sensor_id, day)`, clustering `measured_at DESC`, compaction TWCS
- `latest_measurement_by_sensor` — une ligne par capteur, écrite avec `USING TIMESTAMP` = date de mesure

## Contrat REST *(à implémenter — étape 8)*
Aucune route n'est encore câblée (les endpoints ne seront actifs qu'une fois
`MeasurementQueryController` implémenté) ; contrat cible :

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
`LatestMeasurementEntity`, factories `from(EnergyMeasurement)`) + tests
(`MeasurementKeyTest`, `CassandraMappingTest` avec Cassandra Testcontainers et
application de `scripts/init-cassandra.cql`).

## À faire (dans l'ordre)
1. `service/CassandraMeasurementWriter` — 2 upserts idempotents, **sans null explicite** (étape 7)
2. `stream/StorageStreamConsumer` — branche le writer sur la boucle partagée (étape 7)
3. `service/HistoryCursor`, `MeasurementQueryService`, `web/MeasurementQueryController` (étape 8)
4. `StorageIntegrationTest` — scénarios 3, 5, 6, 8 (cf. `docs/tests.md`)

## Pièges
- **ACK après persistance** uniquement, sinon perte silencieuse.
- Une requête **par jour** de la plage ; jamais de `IN` sur la clé de partition.
- Null explicite = tombstone : omettre les colonnes absentes.
- Cassandra met 1 à 2 minutes à démarrer (le Compose attend `cassandra-init`).
- Dans `application.yml`, le bloc `cassandra:` doit être **sous `data:`** (Spring lit
  `spring.data.cassandra.*`) ; s'il est au même niveau que `data:`, tout le bloc est
  ignoré silencieusement et le driver retombe sur `127.0.0.1:9042`.

## Critère de validation (étape 7)
Les mesures envoyées au collector apparaissent dans les tables Cassandra (vérification
via `cqlsh` à l'étape 7 ; via `GET /api/measurements/{sensorId}` une fois l'étape 8
livrée) ; l'arrêt puis le redémarrage du service ne perd rien.
