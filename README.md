# SmartEnergy Hub — MVP

Plateforme distribuée de collecte, de stockage et d'analyse descriptive de mesures
énergétiques. Voir le cahier des charges pour le périmètre complet.

**Chaîne** : Simulateur → Collector (Spring Boot) → Redis Streams → Storage (Spring Boot)
→ Cassandra + MongoDB ; Analytics/API Service (Spring Boot + Swagger) pour la consultation.

## 1. Prérequis

| Outil | Version testée |
|---|---|
| JDK | 17 |
| Maven | 3.8+ (ou wrapper `./mvnw`) |
| Docker | 28.x |
| Docker Compose | v2/v5 (`docker-compose`) |
| Python | 3.12 (optionnel, pour lancer le simulateur hors Docker) |

Machine recommandée : ≥ 8 Go RAM (limites mémoire définies par conteneur).

## 2. Arborescence

```
SmartEnrgyHub/
├── docker-compose.yml        # infrastructure + services (profil app)
├── .env                      # ports, URLs, identifiants de dev (ENF-02)
├── docs/                     # architecture, contrat, API, décisions, tests
├── simulator/                # simulateur Python
├── collector-service/        # Spring Boot
├── storage-service/          # Spring Boot
├── analytics-service/        # Spring Boot + OpenAPI
├── scripts/                  # init-cassandra.cql, sample-data.json
└── preuves/                  # tests/, captures/
```

## 3. Démarrage

### 3.1 Infrastructure seule (recommandé en développement)

```bash
docker-compose up -d
docker-compose ps
```

Attendre que `redis`, `cassandra` et `mongodb` soient `healthy` (Cassandra ~60-90 s).

### 3.2 Initialiser le schéma Cassandra

```bash
docker exec -i smartenergy-cassandra cqlsh < scripts/init-cassandra.cql
```

### 3.3 Compiler / lancer un service applicatif en local

```bash
cd collector-service && ./mvnw spring-boot:run      # http://localhost:8081
cd storage-service   && ./mvnw spring-boot:run      # http://localhost:8082
cd analytics-service && ./mvnw spring-boot:run      # http://localhost:8083
```

### 3.4 Stack complète (infra + services + simulateur)

```bash
docker-compose --profile app up -d --build
```

- Collector : http://localhost:8081/actuator/health
- Storage : http://localhost:8082/actuator/health
- Analytics/API + Swagger : http://localhost:8083/swagger-ui.html

### 3.5 Simulateur hors Docker

```bash
cd simulator
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
SIMULATOR_COLLECTOR_URL=http://localhost:8081/api/measurements python3 simulator.py
```

## 4. Vérifications rapides

```bash
# Mesure -> Collector (attendu 202)
curl -i -X POST http://localhost:8081/api/measurements \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-001","timestamp":"2026-09-30T08:15:00Z","powerKw":3.72,"voltage":226.4,"temperature":29.1}'

# Contenu du stream
docker exec smartenergy-redis redis-cli XLEN energy-measurements

# Santé des services
curl http://localhost:8083/actuator/health
```

## 5. Tests

```bash
cd collector-service && ./mvnw test
cd storage-service   && ./mvnw test
cd analytics-service && ./mvnw test
```

Voir `docs/tests.md` pour la matrice de tests (T01–T14).

## 6. Arrêt

```bash
docker-compose --profile app down      # arrête tout (volumes conservés)
docker-compose --profile app down -v   # arrête ET supprime les volumes
```

## 7. Configuration

Toute la configuration vit dans `.env` (ports, URLs, identifiants, limites mémoire).
Les services lisent des variables d'environnement (ENF-02). Aucun secret réel n'est commité.

## 8. Périmètre / hors périmètre

**Inclus** : simulateur, Collector, Redis Streams, Storage, Cassandra, MongoDB,
Analytics/API, OpenAPI, Docker Compose, tests.

**Exclu du MVP** : prévision IA, dashboard web, API Gateway, JWT, alertes externes,
Kafka/Spark, Kubernetes, Prometheus/Grafana.

## 9. État d'avancement

- [x] Arborescence, configuration externe, Docker Compose (infra + profils + limites).
- [x] Squelettes Spring Boot compilables (Collector, Storage, Analytics + OpenAPI).
- [x] Simulateur Python.
- [x] Schéma Cassandra + jeux de données d'exemple.
- [x] Documentation (`docs/`).
- [ ] Contrôleurs/validation métier (Collector).
- [ ] Consumer Redis + persistance (Storage).
- [ ] Endpoints capteurs / historique / analytics (Analytics).
- [ ] Tests T01–T14 et preuves.
