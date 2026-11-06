# SmartEnergy Hub — MVP

[![CI](https://github.com/MixMaster2005/SmartEnergyHub/actions/workflows/ci.yml/badge.svg)](https://github.com/MixMaster2005/SmartEnergyHub/actions/workflows/ci.yml)

Plateforme distribuée de collecte, de stockage et d'analyse descriptive de mesures
énergétiques. Voir le cahier des charges pour le périmètre complet — **MVP : `docs/cdc.md` ;
extensions hors MVP : `docs/cdc.md` §18** (identifiants `EXT-9C`, `EXT-11`, `EXT-12`,
`EXT-GW`, `EXT-UI`, `EXT-B`, repris en §10.2 et §11 ci-dessous).

**Chaîne** : Simulateur (Java) → Collector (Spring Boot) → Redis Streams →
Storage (Spring Boot) → Cassandra ; Analytics/API (Spring Boot + Swagger) pour la
consultation ; Sensor Service (Spring Boot) pour le référentiel des capteurs.

## Quickstart

```bash
make up        # copie .env.example -> .env, build des images, démarrage (gateway incluse)
# attendre healthy : redis, mongo, cassandra (~60-90 s) + cassandra-init
make sim       # injecter des mesures (5 capteurs, 2 min)
open http://localhost:8080/swagger-ui.html   # passerelle : Swagger agrégé
open http://localhost:8080/                  # interface EXT-UI (tableau de bord)
make ui-test                                 # 84 tests unitaires de l'interface
# accès directs conservés : :8081 à :8084 /actuator/health (D-25)
```

`make help` liste tous les raccourcis (§8). Détails (infra seule, run local, vérifications,
arrêt) dans les sections 3 à 6 ; commandes de cycle de vie dans `./infra/` (§9).

## 1. Prérequis

| Outil | Version testée |
|---|---|
| JDK | 17 |
| Maven | 3.8+ (build racine multi-modules, pas de wrapper) |
| Docker | 28.x |
| Docker Compose | v2 (`docker compose`) |

Machine recommandée : ≥ 8 Go RAM (limites mémoire définies par conteneur).

## 2. Arborescence

```
SmartEnergyHub/
├── docker-compose.yml        # infra + services applicatifs
├── docker-compose.override.yml  # publie les ports des bases (dev uniquement)
├── .env / .env.example       # ports, URLs, identifiants de dev (ENF-02)
├── Dockerfile                # image unique paramétrée par SERVICE=<module>
├── Makefile                  # raccourcis : build, up, sim, e2e, todo...
├── docs/                     # architecture, CDC, décisions, sécurité, tests
├── contracts/                # modules Maven de contrats partagés (+ schémas JSON)
├── platform/                 # platform-web (erreurs RFC 9457) + platform-stream (consumer Redis)
├── simulator/                # simulateur Java (Spring Boot, CLI)
├── collector-service/        # ingestion des mesures
├── storage-service/          # persistance Cassandra
├── sensor-service/           # référentiel des capteurs (MongoDB)
├── analytics-service/        # agrégats/anomalies + API Swagger
├── gateway-service/          # EXT-GW : passerelle (routage, CORS, Swagger agrégé)
├── frontend/                 # EXT-UI : interface Web statique (modules ES, Chart.js vendorisé)
├── tools/capture/            # captures Playwright des vues + rapport JSON
├── ml/ + models/             # EXT-9C : pipeline de prévision IA (extension, squelette)
├── scripts/                  # init-cassandra.cql, init-mongo.js, e2e.sh, perf.sh
├── infra/                    # scripts de cycle de vie (linux/, windows/)
└── preuves/                  # tests/, captures/
```

## 3. Démarrage

### 3.1 Plateforme complète (infra + services applicatifs)

```bash
cp .env.example .env          # si .env absent (make up le fait aussi)
docker compose --profile gateway up -d --build
docker compose ps
```

Équivalent : `make up` (copie `.env`, build et démarrage en une commande, profil `gateway` inclus).

Attendre que `redis`, `mongo` et `cassandra` soient `healthy` (Cassandra ~60-90 s),
puis que `cassandra-init` se termine (le schéma Cassandra est créé automatiquement).

Ports publiés (dev) : Gateway `:8080` (optionnelle mais démarrée par `make up`),
Collector `:8081`, Storage `:8082`, Analytics/API `:8083`, Sensor `:8084`.
Bases : Redis `:6379`, MongoDB `:27017`, Cassandra `:9042`
(uniquement via `docker-compose.override.yml`).

- Gateway + Swagger agrégé : http://localhost:8080/swagger-ui.html
- Accueil = interface EXT-UI : http://localhost:8080/
- Collector : http://localhost:8081/actuator/health
- Storage : http://localhost:8082/actuator/health
- Analytics/API + Swagger : http://localhost:8083/swagger-ui.html
- Sensor : http://localhost:8084/actuator/health

La gateway est **additive** (D-25) : les ports 8081–8084 restent publiés pour l'accès
direct (IDE, diagnostic, Swagger par service). Les routes d'exploitation passent aussi
par la passerelle : `/collector/...`, `/storage/...`, `/analytics/...`, `/sensor/...`
(préfixe retiré, D-26).

### 3.2 Infrastructure seule (sans les services applicatifs)

```bash
docker compose up -d redis mongo cassandra cassandra-init
```

Le schéma Cassandra est initialisé par le conteneur `cassandra-init`
(exécution unique de `scripts/init-cassandra.cql`). En cas de besoin manuel :

```bash
docker exec -i smartenergy-cassandra cqlsh < scripts/init-cassandra.cql
```

### 3.3 Compiler / lancer un service applicatif en local

Le build est multi-modules à la racine (pas de `mvnw` par module) :

```bash
# Compiler un service + ses dépendances (contrats, platform)
mvn -pl collector-service -am package -DskipTests

# Lancer (exemples ; le port local par défaut est dans chaque application.yml)
java -jar collector-service/target/app.jar   # http://localhost:8081
java -jar storage-service/target/app.jar     # http://localhost:8082
java -jar analytics-service/target/app.jar   # http://localhost:8083
java -jar sensor-service/target/app.jar      # http://localhost:8084
java -jar gateway-service/target/app.jar     # http://localhost:8080 (URIs par défaut : 8081-8084)
```

Alternative développeur (nécessite un `mvn -pl <module> -am install -DskipTests`
préalable pour résoudre les dépendances locales) :

```bash
mvn -pl collector-service spring-boot:run
```

Les services en local se connectent aux bases dockerisées grâce aux ports
publiés par `docker-compose.override.yml` et aux variables d'env de `.env`.

### 3.4 Simulateur

Le simulateur est une application Java (Spring Boot, sans serveur web).
Détails et options (durée, nombre de capteurs, pics d'anomalie) :
voir `simulator/README.md`.

```bash
docker compose run --rm simulator    # 5 capteurs, 2 minutes par défaut
make sim                             # raccourci Makefile
```

## 4. Vérifications rapides

```bash
# Mesure -> Collector (attendu 202) via la gateway
curl -i -X POST http://localhost:8080/api/measurements \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-001","timestamp":"2026-09-30T08:15:00Z","powerKw":3.72,"voltage":226.4,"temperature":29.1}'

# Contenu du stream
docker exec smartenergy-redis redis-cli -a change-me --no-auth-warning XLEN energy-measurements

# Santé : gateway puis services (accès direct)
curl http://localhost:8080/actuator/health
curl http://localhost:8083/actuator/health
```

## 5. Tests

```bash
mvn -B test
```

Voir `docs/tests.md` pour la matrice de recette (scénarios 1–11) et
`preuves/tests/resultats-modele.md` pour le gabarit de preuves T01–T14.

### 5.1 Intégration continue (CI)

Le workflow GitHub Actions `.github/workflows/ci.yml` se déclenche sur chaque
**pull request vers `main`** et chaque push sur `main` :

| Job | Contenu |
|---|---|
| `build-test` | JDK 17 + Maven : `mvn -B -ntp test` (102 tests, Testcontainers Redis/Mongo/Cassandra) |
| `frontend-test` | Node 22 : `npm test` dans `frontend/` (84 tests `node --test`, sans dépendance) |
| `e2e` | `docker compose --profile gateway config`, syntaxe des scripts, `make up`, attente de la gateway, `./scripts/e2e.sh` (scénarios 1–11, 413, contrôles EXT-GW et EXT-UI), nettoyage `down -v` |
| `Captures EXT-UI` | workflow **manuel** `captures.yml` : `make up`, Playwright, 6 captures + rapport publiés en artefact |

Pour rendre le contrôle **obligatoire** avant fusion : *Settings → Branches →
Branch protection rules* sur `main`, puis cocher *Require status checks to pass
before merging* et sélectionner `Compilation et tests (Maven + Testcontainers)`,
`Tests unitaires de l'interface EXT-UI` et `Recette de bout en bout (Compose + gateway)`.
Les rapports Surefire sont publiés en artefact en cas d'échec.

## 6. Arrêt

```bash
docker compose down      # arrête tout (volumes conservés)  — équivalent : make down
docker compose down -v   # arrête ET supprime les volumes (re-joue les inits) — make reset
```

## 7. Configuration

Toute la configuration vit dans `.env` (copie de `.env.example`) : mots de passe,
ports, paramètres du simulateur, origines CORS de la gateway. Les services lisent des
variables d'environnement (ENF-02) ; les valeurs par défaut des `application.yml`
permettent un lancement local sans Docker. Aucun secret réel n'est commité.

La gateway accepte aussi `COLLECTOR_URI`, `STORAGE_URI`, `ANALYTICS_URI`, `SENSOR_URI`
(défauts locaux `http://localhost:8081` à `8084`, remplacés par les noms Compose) et
`CORS_ALLOWED_ORIGINS` (défaut `http://localhost:3000,http://localhost:5173`).

## 8. Raccourcis Makefile

`make help` liste les cibles ; `make todo` liste les classes restant à implémenter.

| Cible | Action |
|---|---|
| `make help` | Liste des commandes |
| `make build` | Compile tout (contrats + services) avec Maven local |
| `make up` | Démarre la plateforme (copie `.env` + build des images inclus) |
| `make down` | Arrête la plateforme (volumes conservés) |
| `make reset` | Arrête ET supprime les volumes (re-joue les scripts d'init) |
| `make ps` | État des conteneurs |
| `make logs` | Logs de tous les services (Ctrl+C pour quitter) |
| `make sim` | Lance le simulateur (5 capteurs, 2 minutes par défaut) |
| `make e2e` | Scénarios de recette HTTP (`scripts/e2e.sh`) |
| `make ui-test` | Tests unitaires de l'interface EXT-UI (`node --test`) |
| `make captures` | Captures Playwright des 6 vues (`tools/capture/`, plateforme up) |
| `make perf` | Mesures de performance (`scripts/perf.sh`, plateforme up) |
| `make todo` | Liste les classes restant à implémenter |
| `make redis-cli` | Shell Redis |
| `make mongosh` | Shell MongoDB (root) |
| `make cqlsh` | Shell Cassandra |

## 9. Outils infra (cycle de vie)

Commandes de cycle de vie (infra + services). Voir `infra/README.md`.

Linux, macOS, WSL, Git Bash :

```bash
./infra/linux/se.sh help
./infra/linux/se.sh doctor
./infra/linux/se.sh first-start
./infra/linux/se.sh start --no-wait
```

Windows (PowerShell / CMD) :

```powershell
infra\windows\se.cmd help
infra\windows\se.cmd doctor
infra\windows\se.cmd first-start
```

Test sans Docker réel : `bash infra/linux/tests/smoke-mock-docker.sh`.

## 10. Périmètre

### 10.1 Inclus (MVP)

Simulateur Java, Collector, Redis Streams, Storage, Cassandra, Sensor Service, MongoDB,
Analytics/API, OpenAPI (springdoc), Docker Compose, tests unitaires + tests d'intégration
Testcontainers, recette de bout en bout (`make e2e`).

### 10.2 Extensions (hors MVP) — par étape

Squelettes et listes d'attente pour la plupart : **`EXT-GW` et `EXT-UI` sont livrés**
(passerelle et interface Web), les autres extensions ne sont pas codées. Le détail
complet (exigences, fichiers de départ, activation, critère de validation) vit dans
**`docs/cdc.md` §18** ; l'avancement dans **`docs/TODO-MAP.md` partie B**.

| ID | Étape du guide | Extension | État | Départs / documentation | Activation |
|---|---|---|---|---|---|
| `EXT-9C` | 9C (J8) | Prévision IA : baseline puis modèle ONNX | squelette livré, **non codé** — endpoint `/api/analytics/{id}/forecast` → 501 `FORECAST_DISABLED` | `analytics-service/.../forecast/ForecastService.java`, `ml/`, `models/` — `ml/README.md` | `ANALYTICS_FORECAST_ENABLED=true` + `smartenergy.analytics.forecast.model-path` |
| `EXT-11` | 11 | Observabilité avancée : Prometheus + dashboard Grafana | aucun fichier (les endpoints `/actuator/prometheus` sont déjà exposés) | `docs/cdc.md` §18.2, `docs/security.md` | profil Compose de monitoring (à créer) |
| `EXT-12` | 12 | Sécurité avancée : JWT, rôles `ADMIN`/`VIEWER`, clé d'API, HTTPS | aucun fichier (le volet MVP de l'étape 12 est livré) | `docs/cdc.md` §18.3, `docs/security.md` | Spring Security + variables d'environnement |
| `EXT-GW` | 10 / 12 | API Gateway : routage, CORS, Swagger agrégé | **livré** (D-25 à D-27) — rate-limit et JWT restent dans `EXT-12` | `gateway-service/README.md` | profil Compose `gateway` sur `:8080` (`make up`) |
| `EXT-UI` | 10 / 12 | Interface Web de consultation : 6 vues, Chart.js vendorisé, scénarios de recette, captures | **livré** (D-28 à D-33) | `frontend/README.md`, `tools/capture/README.md` | `make up` → http://localhost:8080/ ; tests `make ui-test`, captures `make captures` |
| `EXT-B` | bonus | Évolutivité : Kafka (D-03), Kubernetes, alertes, OAuth2 | réflexion seulement | `docs/cdc.md` §18.5 | sur signaux (`energy_stream_lag`, besoins utilisateurs) |

### 10.3 Hors périmètre (non prévu, même en extension)

Multi-tenant, facturation, pilotage d'équipements (commande des capteurs), haute disponibilité
multi-nœuds réelle de Cassandra (CDC §1.3).

## 11. État d'avancement

### MVP

- [x] Arborescence, configuration externe (`.env.example`), Docker Compose.
- [x] Contrats partagés (`contracts/`) + tests unitaires.
- [x] `platform-web` (erreurs RFC 9457, X-Request-Id) et `platform-stream`
      (boucle consumer XREADGROUP/ACK/DLQ étape 4, métriques de stream
      `energy_stream_pending`/`energy_stream_lag` étape 11).
- [x] Collector : validation temporelle, endpoint d'ingestion, publication Redis
      Streams (étapes 1–3) + tests unitaires.
- [x] Sensor Service : CRUD complet (MongoDB) + tests unitaires.
- [x] Simulateur Java + `simulator/README.md`.
- [x] Schéma Cassandra/Mongo + scripts d'initialisation automatiques.
- [x] Documentation (`docs/`).
- [x] Storage : étape 5 (entités Cassandra + tests de mapping Testcontainers).
- [x] Storage : étape 7 (writer idempotent asynchrone, consumer, tests Testcontainers).
- [x] Analytics : étape 9 (règles, agrégats, consumer, dédup).
- [x] Étape 8 (API de consultation : historique paginé + latest storage,
      summary/daily-summary/anomalies/exceedances analytics, alias D-17,
      forecast déclaré en 501, OpenAPI).
- [x] Étape 12 (sécurité : limite 10 Ko sur `POST /api/measurements` → 413
      `application/problem+json`, `MEASUREMENT_MAX_BODY_BYTES`).
- [x] Tests d'intégration des quatre services (`CollectorIntegrationTest`,
      `StorageIntegrationTest`, `AnalyticsIntegrationTest`,
      `SensorServiceIntegrationTest` — Testcontainers,
      scénarios 1–11 couverts) + recette de bout en bout automatisée
      (`make e2e` → `scripts/e2e.sh`) et mesures de performance
      (`scripts/perf.sh`).
- [x] Tests `sensor-service` restants (3 cas — `docs/TODO-MAP.md` partie A) :
      version périmée → 409, `DuplicateKeyException` → 409, intégration
      Testcontainers MongoDB (création/lecture + critère étape 6).

### Extensions (hors MVP)

- [ ] `EXT-9C` Prévision IA (étape 9C) — squelette : `ForecastService` (TODO), `ml/`, `models/` ; endpoint en 501.
- [ ] `EXT-11` Observabilité avancée Prometheus/Grafana (étape 11) — métriques exposées, dashboard à créer.
- [ ] `EXT-12` JWT + rôles, clé d'API, HTTPS (étape 12) — aucun fichier.
- [x] `EXT-GW` API Gateway (étapes 10/12) — `gateway-service/` livré : 5 routes métier (D-17),
      routes ops + Swagger agrégé + CORS + accueil frontend, profil Compose `gateway`,
      ports directs conservés (D-25) et délais de recette élargis (D-27).
- [x] `EXT-UI` interface Web (D-28 à D-33) — `frontend/` livré : modules ES sans build,
      Chart.js 4.4.7 vendorisé, 6 vues (dashboard, capteurs, analyses, système, scénarios),
      84 tests Node, captures Playwright dans `preuves/captures/ext-ui/`, contrôle EXT-UI d'e2e.
- [ ] `EXT-B` Kafka, Kubernetes, alertes (bonus) — sur signaux.

Voir `docs/TODO-MAP.md` (parties A et B) pour la liste à jour des fichiers restants.
