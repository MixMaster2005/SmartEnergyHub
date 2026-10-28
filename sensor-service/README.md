# sensor-service (port local 8084, 8080 dans Docker)

**Rôle** : registre des capteurs (métadonnées et seuil `maxPowerKw`) — EF-05. CRUD complet,
testé unitairement et en intégration (Testcontainers MongoDB). **Propriétaire** de MongoDB `sensor_db` (utilisateur `sensor_app`,
droits limités à cette base).

## Contrat
| Route | Réponses |
|---|---|
| `POST /api/sensors` | 201 + `Location` ; 400 ; 409 `SENSOR_ALREADY_EXISTS` |
| `GET /api/sensors?page&size&building&active` | 200 `PageResponse` (size max 100) |
| `GET /api/sensors/{id}` | 200 ; 404 `SENSOR_NOT_FOUND` |
| `PUT /api/sensors/{id}` | 200 ; 400 ; 404 ; 409 `VERSION_CONFLICT` |
| `DELETE /api/sensors/{id}` | 204 (désactivation logique) ; 404 |

`PUT` : le `sensorId` du corps doit égaler celui de l'URL ; `version` optionnelle (verrou optimiste).
`GET /api/sensors/{id}/daily-summary` **n'est pas ici** : il est servi par analytics-service (décision D-17).

Détails comportementaux :
- pagination : `page=0` par défaut, `size=20`, tri par `sensorId` ; `page<0` ou `size` hors `[1,100]` → 400 `INVALID_PARAMETER` ;
- `active` absent du corps à la création → `true` ;
- `DELETE` = désactivation logique (`active=false` + mise à jour de `updatedAt`) ;
- `409` aussi en cas de course sur l'index unique (`DuplicateKeyException` attrapée).

## Données
`sensors` : `sensorId` (index unique `uk_sensorId`), `name`, `building` (index `ix_building`),
`floor`, `maxPowerKw`, `active`, `tags`, `version`, `createdAt`, `updatedAt`.
Les index sont créés par `scripts/init-mongo.js` (pas par Spring).

## Configuration (`application.yml`)
`SERVER_PORT` (8084 en local, 8080 dans Docker) · `MONGO_HOST/PORT` ·
`MONGO_SENSOR_PASSWORD` (utilisateur `sensor_app`) · Actuator : `health,info,metrics,prometheus`,
readiness `mongo`. Les 5 routes portent des annotations OpenAPI (springdoc via platform-web).

## Tests
`SensorServiceTest` (Mockito, sans base) — 7 tests : création, doublon, `sensorId` incohérent,
inconnu (PUT), taille de page, **`PUT` avec `version` périmée → 409 `VERSION_CONFLICT`
(version courante acceptée), `DuplicateKeyException` sur l'index unique → 409 `SENSOR_ALREADY_EXISTS`**.

`SensorServiceIntegrationTest` (Testcontainers MongoDB `mongo:7`, `@SpringBootTest`) — 3 tests :
création (201 + `Location`) puis lecture (dont filtre `building`), **métadonnées consultables avec
Cassandra arrêté** (critère étape 6 : aucun conteneur Cassandra dans la suite et aucun driver
`CqlSession` au classpath), **doublon → 409 `application/problem+json` + index `uk_sensorId`
présent et insertion brute en double refusée par MongoDB**. L'index unique est créé
programmatiquement (équivalent de `scripts/init-mongo.js`).

## Pièges
- L'unicité repose sur l'**index** MongoDB (course entre deux créations) ; le contrôle `existsBySensorId` ne suffit pas seul — le rattrapage est le `catch (DuplicateKeyException)` → 409.
- Les index ne sont pas créés par Spring (auto-index-creation non activée explicitement) : ils viennent du script d'initialisation.

## Extension (hors MVP)

Gabarit du CDC §18 — **rien n'est codé**.

- **`EXT-12` — Sécurité avancée (étape 12)** — *aucun fichier*.
  Actuellement les routes d'écriture `POST/PUT/DELETE /api/sensors` sont **publiques**
  (état documenté dans `docs/security.md`) ; l'extension ajoute Spring Security avec les rôles
  **`ADMIN`** (écriture) et **`VIEWER`** (lecture), idéalement vérifiés au niveau de la gateway.
  - Activation : Spring Security + JWT (variables d'environnement, secrets hors Git).
  - Critère : écriture sans token `ADMIN` → 401/403 `application/problem+json` ; lecture `GET /api/sensors` selon le rôle exigé.
  - Renvois : CDC §18.3 · `docs/TODO-MAP.md` partie B · `docs/security.md` · README racine §10.2.
- **`EXT-GW` — API Gateway (étapes 10/12)** — *scaffolding non codé* : après activation, ce service
  n'est plus publié sur `:8084` mais atteint via `:8080` (attention à l'ordre des règles :
  `/api/sensors/*/daily-summary` → analytics **avant** `/api/sensors/**` → sensor, CDC §7.6).
  - Renvois : CDC §18.4 · `gateway-service/README.md`.
