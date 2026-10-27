# SmartEnergy Hub — MVP

Plateforme distribuée de collecte, de stockage et d'analyse descriptive de mesures
énergétiques. Voir le cahier des charges pour le périmètre complet — **MVP : `docs/cdc.md` ;
extensions hors MVP : `docs/cdc.md` §18** (identifiants `EXT-9C`, `EXT-11`, `EXT-12`,
`EXT-GW`, `EXT-B`, repris en §8.2 et §10 ci-dessous).

**Chaîne** : Simulateur (Java) → Collector (Spring Boot) → Redis Streams →
Storage (Spring Boot) → Cassandra ; Analytics/API (Spring Boot + Swagger) pour la
consultation ; Sensor Service (Spring Boot) pour le référentiel des capteurs.

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
├── gateway-service/          # EXT-GW : extension non codée (fichiers .example)
├── ml/ + models/             # EXT-9C : pipeline de prévision IA (extension, squelette)
├── scripts/                  # init-cassandra.cql, init-mongo.js, e2e.sh, perf.sh
├── infra/                    # scripts de cycle de vie (linux/, windows/)
└── preuves/                  # tests/, captures/
```

## 3. Démarrage

### 3.1 Plateforme complète (infra + services applicatifs)

```bash
cp .env.example .env          # si .env absent (make up le fait aussi)
docker compose up -d --build
docker compose ps
```

Attendre que `redis`, `mongo` et `cassandra` soient `healthy` (Cassandra ~60-90 s),
puis que `cassandra-init` se termine (le schéma Cassandra est créé automatiquement).

Ports publiés (dev) : Collector `:8081`, Storage `:8082`, Analytics/API `:8083`,
Sensor `:8084`. Bases : Redis `:6379`, MongoDB `:27017`, Cassandra `:9042`
(uniquement via `docker-compose.override.yml`).

- Collector : http://localhost:8081/actuator/health
- Storage : http://localhost:8082/actuator/health
- Analytics/API + Swagger : http://localhost:8083/swagger-ui.html
- Sensor : http://localhost:8084/actuator/health

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
# Mesure -> Collector (attendu 202)
curl -i -X POST http://localhost:8081/api/measurements \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-001","timestamp":"2026-09-30T08:15:00Z","powerKw":3.72,"voltage":226.4,"temperature":29.1}'

# Contenu du stream
docker exec smartenergy-redis redis-cli -a change-me --no-auth-warning XLEN energy-measurements

# Santé des services
curl http://localhost:8083/actuator/health
```

## 5. Tests

```bash
mvn -B test
```

Voir `docs/tests.md` pour la matrice de recette (scénarios 1–11) et
`preuves/tests/resultats-modele.md` pour le gabarit de preuves T01–T14.

## 6. Arrêt

```bash
docker compose down      # arrête tout (volumes conservés)
docker compose down -v   # arrête ET supprime les volumes (re-joue les inits)
```

## 7. Configuration

Toute la configuration vit dans `.env` (copie de `.env.example`) : mots de passe,
ports, paramètres du simulateur. Les services lisent des variables
d'environnement (ENF-02) ; les valeurs par défaut des `application.yml`
permettent un lancement local sans Docker. Aucun secret réel n'est commité.

## 8. Périmètre

### 8.1 Inclus (MVP)

Simulateur Java, Collector, Redis Streams, Storage, Cassandra, Sensor Service, MongoDB,
Analytics/API, OpenAPI (springdoc), Docker Compose, tests unitaires + tests d'intégration
Testcontainers, recette de bout en bout (`make e2e`).

### 8.2 Extensions (hors MVP) — par étape

Squelettes et listes d'attente uniquement : **rien n'est codé**. Le détail complet (exigences,
fichiers de départ, activation, critère de validation) vit dans **`docs/cdc.md` §18** ;
l'avancement dans **`docs/TODO-MAP.md` partie B**.

| ID | Étape du guide | Extension | État | Départs / documentation | Activation |
|---|---|---|---|---|---|
| `EXT-9C` | 9C (J8) | Prévision IA : baseline puis modèle ONNX | squelette livré, **non codé** — endpoint `/api/analytics/{id}/forecast` → 501 `FORECAST_DISABLED` | `analytics-service/.../forecast/ForecastService.java`, `ml/`, `models/` — `ml/README.md` | `ANALYTICS_FORECAST_ENABLED=true` + `smartenergy.analytics.forecast.model-path` |
| `EXT-11` | 11 | Observabilité avancée : Prometheus + dashboard Grafana | aucun fichier (les endpoints `/actuator/prometheus` sont déjà exposés) | `docs/cdc.md` §18.2, `docs/security.md` | profil Compose de monitoring (à créer) |
| `EXT-12` | 12 | Sécurité avancée : JWT, rôles `ADMIN`/`VIEWER`, clé d'API, HTTPS | aucun fichier (le volet MVP de l'étape 12 est livré) | `docs/cdc.md` §18.3, `docs/security.md` | Spring Security + variables d'environnement |
| `EXT-GW` | 10 / 12 | API Gateway : routage, CORS, Swagger agrégé, rate-limit | scaffolding non codé (fichiers `.example`) | `gateway-service/README.md` | profil Compose `gateway` sur `:8080` |
| `EXT-B` | bonus | Évolutivité : Kafka (D-03), Kubernetes, interface Web, alertes, OAuth2 | réflexion seulement | `docs/cdc.md` §18.5 | sur signaux (`energy_stream_lag`, besoins utilisateurs) |

### 8.3 Hors périmètre (non prévu, même en extension)

Multi-tenant, facturation, pilotage d'équipements (commande des capteurs), haute disponibilité
multi-nœuds réelle de Cassandra (CDC §1.3).

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

## 10. État d'avancement

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
- [x] Tests d'intégration des trois services (`CollectorIntegrationTest`,
      `StorageIntegrationTest`, `AnalyticsIntegrationTest` — Testcontainers,
      scénarios 1–11 couverts) + recette de bout en bout automatisée
      (`make e2e` → `scripts/e2e.sh`) et mesures de performance
      (`scripts/perf.sh`).
- [ ] Tests `sensor-service` restants (3 cas — `docs/TODO-MAP.md` partie A).

### Extensions (hors MVP) — rien de codé (CDC §18)

- [ ] `EXT-9C` Prévision IA (étape 9C) — squelette : `ForecastService` (TODO), `ml/`, `models/` ; endpoint en 501.
- [ ] `EXT-11` Observabilité avancée Prometheus/Grafana (étape 11) — métriques exposées, dashboard à créer.
- [ ] `EXT-12` JWT + rôles, clé d'API, HTTPS (étape 12) — aucun fichier.
- [ ] `EXT-GW` API Gateway (étapes 10/12) — scaffolding `gateway-service/*.example`.
- [ ] `EXT-B` Kafka, Kubernetes, interface Web, alertes (bonus) — sur signaux.

Voir `docs/TODO-MAP.md` (parties A et B) pour la liste à jour des fichiers restants.
