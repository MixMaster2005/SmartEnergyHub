# Architecture — SmartEnergy Hub (MVP)

> Document de référence déduit du *Cahier des charges SmartEnergy Hub — MVP obligatoire v1.0*
> et du *Projet_Complet_Spring_Boot_Guide.pdf*.

## 1. Objectif

Plateforme distribuée qui **génère, collecte, transporte, persiste et consulte** des
mesures énergétiques, avec calcul d'indicateurs descriptifs (moyenne, min, max, énergie
estimée) et détection simple de dépassement de seuil (`powerKw > maxPowerKw`).

## 2. Chaîne fonctionnelle

```
+-------------+   HTTP POST    +-------------------+   XADD    +--------------------+
|  Simulateur | -------------> |  Collector        | --------> |  Redis Streams     |
|  (Python)   |  /api/measure  |  Service (Boot)   |           | energy-measurements|
+-------------+   ments        |  validation       |           |  consumer group    |
                               |  HTTP 202 / 400   |           |  storage-group     |
                               +-------------------+           +---------+----------+
                                                                        | XREADGROUP
                                                                        v
                                                             +--------------------+
                                                             |  Storage Service   |
                                                             |  (Boot)            |
                                                             +----+----------+----+
                                                                  |          |
                                                     ecriture     |          |  ecriture
                                                  mesures brutes  |          |  metadonnees
                                                                  v          v
                                                        +-----------+  +-----------+
                                                        | Cassandra |  |  MongoDB  |
                                                        | mesures   |  | capteurs  |
                                                        | temporell.|  | agregats  |
                                                        +-----+-----+  +-----+-----+
                                                              |              |
                                                              +------+-------+
                                                                     | lecture
                                                                     v
                                                          +----------------------+
                                                          | Analytics / API Svc  |
                                                          | (Boot + Swagger)     |
                                                          | historique, latest,  |
                                                          | summary, anomalies   |
                                                          +----------------------+
```

## 3. Composants et responsabilités

| Composant | Rôle | Ne doit pas faire |
|---|---|---|
| **Simulateur** (`simulator/`) | Génère des mesures plausibles pour ≥ 5 capteurs, intervalle configurable, injecte un pic (×3). | Écrire dans Cassandra/MongoDB. |
| **Collector Service** (`collector-service/`) | Valide le JSON, publie dans Redis Streams, répond `202` si accepté, `400` sinon. | Attendre la persistance avant de répondre. |
| **Redis Streams** | Tampon d'événements + consumer group `storage-group`. | Servir de stockage historique permanent. |
| **Storage Service** (`storage-service/`) | Lit les événements, écrit dans Cassandra, met à jour MongoDB, `XACK` après succès. | Accuser avant la persistance. |
| **Cassandra** | Mesures horodatées, historique par capteur/période. | Remplacer le référentiel documentaire. |
| **MongoDB** | Métadonnées capteurs (dont `maxPowerKw`) + agrégats. | Stocker toutes les mesures brutes. |
| **Analytics / API Service** (`analytics-service/`) | Lecture, calculs descriptifs, endpoints REST + OpenAPI. | Introduire de l'IA/prévision. |
| **Docker Compose** | Orchestration, réseau, volumes, healthchecks. | Remplacer les tests applicatifs. |

## 4. Flux nominal (résumé)

1. Le simulateur crée `{sensorId, timestamp, powerKw, voltage, temperature}`.
2. `POST /api/measurements` sur le Collector.
3. Le Collector valide. Invalide → `400`, **aucun** événement publié.
4. Valide → `XADD` dans `energy-measurements` → réponse `202`.
5. Le Storage Service lit via `storage-group`.
6. Écrit la mesure dans Cassandra, met à jour les agrégats MongoDB.
7. Après succès des écritures obligatoires → `XACK`.
8. L'Analytics/API expose historique, dernière mesure, résumé et anomalies.

## 5. Choix de simplification (équipe de 4)

- On garde les responsabilités logiques distinctes mais **3 services applicatifs** :
  Collector, Storage, Analytics/API (l'API de consultation et les calculs descriptifs
  sont regroupés dans ce dernier).
- Aucune interface Web, pas d'API Gateway, pas de JWT, pas de monitoring avancé.
- Persistance issue du guide : **partition Cassandra `(sensor_id, day)`** pour éviter
  des partitions géantes sur plusieurs années.

## 6. Déploiement

- Réseau bridge dédié `smartenergy-net`.
- Volumes nommés persistants : `smartenergy_redis_data`, `smartenergy_cassandra_data`,
  `smartenergy_mongo_data` (ENF-04).
- Healthchecks sur les 3 bases ; les services applicatifs `depends_on: service_healthy`.
- **Deux profils Compose** :
  - défaut : infrastructure seule (Redis, Cassandra, MongoDB) — adapté à une machine limitée ;
  - `--profile app` : infrastructure + Collector + Storage + Analytics + Simulateur.

## 7. Limites de ressources (limitation de volume/mémoire par conteneur)

Machine de développement : 7,2 Go RAM. Budget alloué :

| Conteneur | `mem_limit` | `mem_reservation` | Remarque |
|---|---|---|---|
| redis | 256 Mo | 96 Mo | AOF activé, petite empreinte |
| cassandra | 1 Go | 512 Mo | heap JVM 512M / young 128M |
| mongodb | 512 Mo | 256 Mo | WiredTiger cache implicite |
| collector-service | 512 Mo | 256 Mo | `MaxRAMPercentage=60` |
| storage-service | 512 Mo | 256 Mo | `MaxRAMPercentage=60` |
| analytics-service | 512 Mo | 256 Mo | `MaxRAMPercentage=60` |
| simulator | 128 Mo | 64 Mo | client HTTP léger |

Les ports ne sont publiés que sur `127.0.0.1` (ENF-07).

## 8. Risques et parades

| Risque | Parade |
|---|---|
| Cassandra démarre lentement | healthcheck `start_period: 90s`, `depends_on condition: service_healthy` |
| Message accusé trop tôt | `XACK` uniquement après écritures (RG-05) |
| Événement traité deux fois | `event_id` unique + traitement idempotent (T11) |
| Partition Cassandra trop grande | partition `(sensor_id, day)` |
| Machine limitée | profils Compose + `mem_limit` + hébergement au besoin |
