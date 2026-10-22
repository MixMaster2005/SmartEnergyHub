# Cahier des Charges Fonctionnel — SmartEnergy Hub (Big Data & Stream)

<aside>
📌

**Statut** : brouillon v0.1 — 16/10/2026. **Source** : guide « Plateforme Big Data & IA pour la supervision énergétique distribuée » (Master 1 MSI, F. Razafindraibe).

**Objet** : décrire quoi construire (exigences), comment les services se parlent (contrats), où vivent les données (propriété par service) et pourquoi chaque choix a été fait (décisions / veille).

**Légende** : **MVP** = obligatoire (niveau 1 du guide) · **EXT** = extension valorisée (niveau 2) · les valeurs chiffrées sont des **cibles proposées**, à confirmer par mesure.

</aside>

---

# 0. Résumé des décisions clés

1. **Un service = une base = un propriétaire.** Aucune base partagée entre services : Cassandra appartient à `storage-service`, MongoDB est scindé en deux bases logiques (`sensor_db` pour `sensor-service`, `analytics_db` pour `analytics-service`).
2. **Écriture asynchrone, lecture synchrone.** Le chemin d'écriture passe par Redis Streams (202 Accepted) ; les consultations passent par REST.
3. **Fan-out via deux consumer groups** sur le même stream : `storage-group` (persistance Cassandra) et `analytics-group` (indicateurs et anomalies). Le stockage n'est donc plus couplé au calcul.
4. **Idempotence par conception** : `eventId` déterministe (UUID v5 de `sensorId` + `timestamp`), clé primaire Cassandra naturelle (un rejeu écrase la même ligne), déduplication côté analytics.
5. **Contrat d'abord** : JSON Schema de l'événement et OpenAPI par service dans un dossier `contracts/` versionné, vérifiés par les tests.
6. **Fiabilité de bout en bout** : ACK Redis uniquement après persistance, reprise des messages en attente, Dead Letter Queue, Redis en AOF avec volume, healthchecks Docker.
7. **Écart assumé avec le guide** (à valider avec l'enseignant) : les agrégats MongoDB sont produits par `analytics-service` et non par `storage-service` (voir section 15).

---

# 1. Contexte, objectifs et périmètre

## 1.1 Contexte

Des bâtiments contiennent des compteurs/capteurs qui émettent périodiquement puissance (kW), tension (V) et température (°C). La plateforme **collecte, stocke, analyse et expose** ces mesures. Un simulateur remplace les capteurs réels.

## 1.2 Objectifs

- Construire une chaîne distribuée vérifiable de bout en bout : simulateur → collecte → Redis Streams → stockage → consultation/analyse.
- Justifier chaque base : Redis (transit, cache), Cassandra (séries temporelles), MongoDB (documents, agrégats).
- Démontrer résilience, observabilité et reproductibilité (une commande Docker Compose).

## 1.3 Périmètre

- **MVP** : simulateur, collector, redis stream, storage, cassandra, mongo, API REST de consultation, moyenne/max/min/énergie + dépassement de seuil, Docker Compose, Swagger, tests de la chaîne complète.
- **EXT** : prévision (régression/LSTM), API Gateway, JWT et rôles, Prometheus/Grafana, alertes automatiques, Kafka, Kubernetes, interface Web.
- **Hors périmètre** : multi-tenant, facturation, pilotage d'équipements (commande des capteurs), haute disponibilité multi-nœuds réelle de Cassandra.

## 1.4 Acteurs

- **Capteur / simulateur** : envoie des mesures.
- **Administrateur** : gère les capteurs et leurs seuils, consulte l'état de santé.
- **Utilisateur / exploitant** : consulte historiques, indicateurs, anomalies, prévisions (rôle VIEWER en extension).
- **Enseignant / évaluateur** : lance le système et rejoue les scénarios de recette.

---

# 2. Exigences fonctionnelles

| ID | Exigence | Service | Niveau | Étape du guide |
| --- | --- | --- | --- | --- |
| EF-01 | Simuler N capteurs (défaut 5) avec profil sinusoïdal + bruit, injecter anomalies, doublons et mesures invalides à la demande | simulator | MVP | 2 |
| EF-02 | Recevoir une mesure, la valider, répondre 202 si publiée dans le stream, 400 si invalide, 503 si le broker est indisponible | collector-service | MVP | 1, 3 |
| EF-03 | Transporter chaque mesure dans le stream `energy-measurements` avec reprise sur panne des consommateurs | collector + Redis | MVP | 4 |
| EF-04 | Persister chaque mesure dans Cassandra de façon idempotente et accuser le message après succès | storage-service | MVP | 5, 7 |
| EF-05 | Créer, lister, lire, modifier un capteur (métadonnées, seuil `maxPowerKw`) ; désactivation logique | sensor-service | MVP | 6 |
| EF-06 | Lire l'historique d'un capteur entre `from` et `to`, trié, paginé | storage-service | MVP | 8 |
| EF-07 | Lire la dernière mesure connue d'un capteur | storage-service | MVP | 8 |
| EF-08 | Calculer sur une période : nombre de mesures, moyenne, min, max, énergie estimée (kWh), dépassements de seuil, anomalies | analytics-service | MVP | 9 (A) |
| EF-09 | Fournir le résumé quotidien d'un capteur (agrégat MongoDB) | analytics-service | MVP | 6, 9 |
| EF-10 | Détecter un dépassement de seuil (puissance supérieure à `maxPowerKw`) et lister les capteurs qui dépassent un seuil donné | analytics-service | MVP | 9 (B) |
| EF-11 | Détecter une anomalie statistique par score z | analytics-service | MVP (simple) / EXT | 9 (B) |
| EF-12 | Lister les anomalies détectées (filtre période et type) | analytics-service | MVP | 8, 9 |
| EF-13 | Prévoir la consommation des prochaines heures (baseline puis modèle IA) | analytics-service | EXT | 9 (C), 18 |
| EF-14 | Exposer santé et métriques (Actuator, Micrometer), dashboard Grafana en extension | tous | MVP / EXT | 11 |
| EF-15 | Ne perdre aucune mesure si un consommateur est arrêté puis relancé | storage, analytics, Redis | MVP | 4, 22 |
| EF-16 | Lancer toute la plateforme avec `docker compose up -d` et un README court | infra | MVP | 10 |
| EF-17 | Documenter toutes les routes en OpenAPI/Swagger avec exemples | tous les services REST | MVP | 8 |
| EF-18 | Sécurité minimale : secrets hors Git, variables d'environnement, profils, validation des entrées ; JWT/rôles en extension | tous | MVP / EXT | 12 |

---

# 3. Architecture

```mermaid
flowchart LR
  SIM["simulator"] -->|"POST /api/measurements"| COL["collector-service<br>:8081"]
  COL -->|"XADD"| RS[("Redis Streams<br>energy-measurements")]
  RS -->|"storage-group"| STO["storage-service<br>:8082"]
  RS -->|"analytics-group"| ANA["analytics-service<br>:8083"]
  STO --> CAS[("Cassandra<br>smartenergy")]
  SEN["sensor-service<br>:8084"] --> M1[("MongoDB<br>sensor_db")]
  ANA --> M2[("MongoDB<br>analytics_db")]
  ANA -->|"GET /api/sensors/id (cache Redis)"| SEN
  USER["Utilisateur / Swagger / Gateway EXT :8080"] --> STO
  USER --> SEN
  USER --> ANA
```

## 3.1 Principes

- **Database per service** : un service n'accède jamais aux tables ou collections d'un autre ; il passe par son API ou par les événements.
- **Sync uniquement quand c'est nécessaire** : l'écriture est asynchrone ; seuls la lecture et la configuration (seuil d'un capteur) sont synchrones.
- **Disponibilité avant cohérence immédiate** : une mesure d'un capteur non référencé est acceptée (seuil par défaut côté analytics).
- **Services sans état** (état dans Redis, Mongo, Cassandra) pour pouvoir lancer plusieurs instances.
- **Configuration 12-factor** : tout paramètre passe par variables d'environnement ou profil Spring.

## 3.2 Catalogue des services

| Service | Port hôte | Responsabilité | Données possédées | Publie / consomme |
| --- | --- | --- | --- | --- |
| simulator | aucun | Générer des mesures et les envoyer | aucune | appelle collector (HTTP) |
| collector-service | 8081 | Valider et publier les mesures | aucune (Redis stream en transit) | publie `energy-measurements` |
| storage-service | 8082 | Persister les mesures et servir l'historique | Cassandra : `measurements_by_sensor_day`, `latest_measurement_by_sensor` | consomme via `storage-group` |
| analytics-service | 8083 | Agrégats, seuils, anomalies, prévision | Mongo `analytics_db` : `sensor_state`, `hourly_aggregates`, `daily_aggregates`, `anomalies`, `forecasts` | consomme via `analytics-group` ; appelle sensor-service (REST) |
| sensor-service | 8084 | Registre des capteurs et seuils | Mongo `sensor_db` : `sensors` | serveur REST |
| gateway-service | 8080 | Point d'entrée unique, JWT, Swagger agrégé | aucune | EXT |

Les ports 8081 à 8083 reprennent le docker-compose du guide ; 8084 et 8080 sont ajoutés. Dans les conteneurs, chaque service écoute sur 8080.

---

# 4. Décisions d'architecture (journal de veille)

| ID | Décision retenue | Alternatives écartées | Justification / conséquence |
| --- | --- | --- | --- |
| D-01 | Quatre services métier + simulateur ; sensor-service séparé | Un monolithe modulaire ; sensors dans analytics | Respecte « chaque microservice a ses données » ; coût : un service de plus à déployer |
| D-02 | Deux consumer groups sur un stream (`storage-group`, `analytics-group`) | Storage qui met à jour Mongo ; second stream | Découple persistance et calcul ; un arrêt d'analytics n'affecte pas le stockage. Écart avec l'étape 7 du guide |
| D-03 | Redis Streams pour le MVP, Kafka en extension | Kafka d'emblée | Plus léger à opérer ; le contrat d'événement reste identique (portable vers Kafka) |
| D-04 | Redis avec AOF (`appendonly yes`, fsync chaque seconde) et volume `redis_data` | Config par défaut (snapshots RDB seulement) | Sans AOF, un redémarrage de Redis perd les messages en attente, ce qui contredirait EF-15 |
| D-05 | ACK après persistance, XAUTOCLAIM pour les messages bloqués, DLQ après 5 livraisons (implémenté par XPENDING + XCLAIM, voir D-22 : Spring Data Redis 3.5 n'expose pas XAUTOCLAIM) | Auto-ACK ; retry infini | Livraison at-least-once ; les messages poison ne bloquent pas le flux |
| D-06 | Rétention du stream par `MAXLEN ~ 500000` (trim approximatif) | Trim par durée ; pas de trim | Mémoire bornée ; le retard des consommateurs est surveillé (métrique `stream_lag`) pour ne jamais approcher la limite |
| D-07 | `eventId` = UUID v5 de `sensorId`  • `|`  • `timestamp` (ms) | UUID aléatoire | Même mesure rejouée donne le même identifiant : déduplication triviale |
| D-08 | Cassandra : partition `(sensor_id, day)`, clustering `measured_at DESC`, TWCS | Partition par capteur seul ; STCS | Partitions bornées (environ 43 200 lignes par jour à 1 mesure/2 s) ; TWCS adaptée aux données temporelles |
| D-09 | Table dédiée `latest_measurement_by_sensor` avec `USING TIMESTAMP` = date de mesure | Lire la dernière ligne de la partition du jour ; LWT | Lecture en O(1) ; une mesure en retard n'écrase jamais une plus récente (last-write-wins sur le temps d'événement) |
| D-10 | Ne jamais écrire de `null` explicite dans Cassandra : omettre les colonnes absentes | Insert avec nulls | Chaque null crée un tombstone, ce qui dégrade les lectures |
| D-11 | Schéma Cassandra créé par script CQL d'init (conteneur one-shot), `schema-action: NONE` | Création automatique par Spring | Schéma explicite, versionné et reproductible |
| D-12 | Healthchecks Compose + `depends_on: condition: service_healthy` | `depends_on` simple (exemple du guide) | Cassandra met souvent 1 à 2 minutes à être prête ; sans healthcheck les services démarrent trop tôt et plantent |
| D-13 | Agrégats analytics mis à jour de façon incrémentale (horaire et quotidien) à partir du stream | Recalcul à la demande depuis Cassandra | Pas de dépendance REST vers storage ; lectures rapides ; limite : les mesures en retard faussent légèrement l'énergie (voir 6.5) |
| D-14 | Micro-batch : lecture de 50 messages, regroupement par capteur, une écriture Mongo par capteur et par lot | Une écriture par message | Réduit fortement les écritures sous charge (EF-14, tests de charge) |
| D-15 | Erreurs REST au format RFC 9457 (`application/problem+json`) | Format maison | Standard, supporté nativement par Spring (`ProblemDetail`) |
| D-16 | Chemins d'API identiques au guide (`/api/...`), sans préfixe de version ; évolution des événements via `schemaVersion` | `/api/v1/...` | Conformité avec les endpoints évalués ; le versioning d'URL peut être ajouté par la gateway |
| D-17 | `GET /api/sensors/{id}/daily-summary` servi par analytics-service (alias de `/api/analytics/{sensorId}/daily-summary`) | Agrégat dans sensor-service | L'agrégat appartient à analytics ; la gateway (EXT) route cette URL vers analytics |
| D-18 | Prévision : baseline d'abord (persistance et saisonnier naïf), puis modèle ; entraînement en Python, export ONNX, inférence Java | Entraîner en Java ; livrer le modèle sans baseline | Un modèle n'a de valeur que s'il bat la baseline (MAE, RMSE) ; l'inférence en Java reste simple |
| D-19 | Tests d'intégration avec Testcontainers (Redis, Mongo, Cassandra) | Mocks ; bases locales | Prouve la chaîne réelle ; reproductible sur toute machine avec Docker |
| D-20 | Résilience des appels REST internes : timeout 2 s, 2 retries, circuit breaker (Resilience4j), cache Redis 60 s | Appel direct sans protection | Analytics continue de fonctionner si sensor-service est indisponible (seuil par défaut) |
| D-21 | Fenêtres temporelles à l'ingestion : mesure ≤ 24 h, futur ≤ 5 min (bornes strictes, 1 ms de dépassement = 400) | Rejet de toute mesure non horodatée « maintenant » ; file de rattrapage pour les mesures > 24 h | 24 h = reprise d'un capteur après coupure réseau (mesures buffered exploitables) ; 5 min = tolérance de dérive d'horloge, au-delà l'ordre temporel n'est plus garanti. Paramétrable (`MEASUREMENT_MAX_AGE` / `MEASUREMENT_MAX_FUTURE`) |
| D-22 | Reprise des messages inactifs par `XPENDING` (détail : compteur de livraisons, temps d'inactivité) + `XCLAIM`, équivalent d'`XAUTOCLAIM` | Commande brute `XAUTOCLAIM` via `redis.execute(...)` | Spring Data Redis 3.5.0 n'expose pas `XAUTOCLAIM` ; l'approche typée reste portable (Lettuce, Jedis) et ne requiert aucun parsing de réponse, au prix d'un aller-retour Redis supplémentaire par passe |

---

# 5. Contrats de communication

## 5.1 Conventions globales

- **Format** : JSON UTF-8, champs en `camelCase`, `Content-Type: application/json`.
- **Temps** : ISO-8601 en **UTC** avec suffixe `Z` (`2026-09-30T08:15:00Z`) ; précision milliseconde au maximum. Les dates calendaires utilisent `YYYY-MM-DD` (UTC).
- **Unités** : puissance `kW`, tension `V`, courant `A`, fréquence `Hz`, température `°C`, énergie `kWh`.
- **Nombres** : nombres JSON (jamais de chaînes) ; point décimal.
- **Intervalles** : `[from, to)` — `from` inclus, `to` exclu. Valeurs par défaut : `to` = maintenant, `from` = `to` moins 24 h.
- **Identifiant capteur** : regex `^[A-Z0-9-]{3,32}$`, par exemple `SENSOR-001`.
- **Corrélation** : en-tête `X-Request-Id` (généré si absent) renvoyé dans la réponse et placé dans `traceId` des événements et des logs.
- **Champs inconnus** : ignorés en entrée (lecteur tolérant) ; jamais de champ obligatoire ajouté sans changer `schemaVersion`.

## 5.2 Contrat d'ingestion : `POST /api/measurements` (collector-service)

**Requête**

```json
{
  "sensorId": "SENSOR-001",
  "timestamp": "2026-09-30T08:15:00Z",
  "powerKw": 3.72,
  "voltage": 226.4,
  "temperature": 29.1,
  "currentA": 16.4,
  "frequencyHz": 50.0,
  "state": "ON"
}
```

**Règles de validation**

| Champ | Obligatoire | Règle |
| --- | --- | --- |
| `sensorId` | oui | non vide, regex `^[A-Z0-9-]{3,32}$` |
| `timestamp` | oui | ISO-8601 ; normalisé en UTC ; pas plus de 5 minutes dans le futur ; pas plus vieux que 24 h (paramètres `measurement.max-future` et `measurement.max-age`) |
| `powerKw` | oui | nombre, de 0 à 1000 inclus |
| `voltage` | oui | nombre, de 0 à 500 inclus (0 = coupure) |
| `temperature` | non | de −50 à 100 |
| `currentA` | non | de 0 à 2000 |
| `frequencyHz` | non | de 40 à 70 |
| `state` | non | `ON`, `OFF`, `STANDBY` ou `FAULT` |

Taille maximale du corps : 10 Ko. Les chaînes à la place de nombres sont rejetées (pas de coercition).

Les bornes temporelles sont strictes : `now − 24 h` et `now + 5 min` sont acceptés, un dépassement d'1 ms est rejeté (400 `MEASUREMENT_TOO_OLD` / `MEASUREMENT_IN_FUTURE`). **Justification des valeurs** : *24 h* — marge de reprise d'un capteur après une coupure réseau, ses mesures buffered restent exploitables ; *5 min* — tolérance de dérive d'horloge entre capteurs, au-delà l'ordre temporel des mesures n'est plus garantiable. Les deux bornes sont paramétrables (`MEASUREMENT_MAX_AGE`, `MEASUREMENT_MAX_FUTURE`).

**Réponses**

| Code | Cas | Corps |
| --- | --- | --- |
| 202 | Mesure valide et publiée dans le stream (XADD réussi) | vide ; en-têtes `X-Event-Id`, `X-Request-Id` |
| 400 | JSON mal formé ou règle de validation violée (format, plage, trop ancien, futur) | Problem Details avec `errors[]` |
| 413 / 415 | Corps trop gros / type de contenu non JSON | Problem Details |
| 503 | Redis indisponible : **jamais de 202 si la publication a échoué** | Problem Details, en-tête `Retry-After: 5` |

**Exemple d'erreur 400**

```json
{
  "type": "https://smartenergy.local/problems/validation-error",
  "title": "Mesure invalide",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "detail": "1 champ invalide",
  "traceId": "b1c2...",
  "errors": [ { "field": "sensorId", "message": "ne doit pas être vide" } ]
}
```

Un doublon (même `sensorId` et même `timestamp`) est **accepté** (202) : il est neutralisé par l'idempotence en aval.

## 5.3 Contrat d'événement : stream Redis `energy-measurements`

Chaque entrée du stream est un ensemble de champs texte (limitation de Redis) :

| Champ | Type logique | Obligatoire | Description |
| --- | --- | --- | --- |
| `schemaVersion` | entier | oui | `1` pour ce contrat |
| `eventId` | UUID | oui | UUID v5 de `sensorId`  • `|`  • `timestamp` |
| `traceId` | texte | oui | valeur de `X-Request-Id` |
| `sensorId` | texte | oui | identifiant capteur |
| `timestamp` | date UTC | oui | heure de mesure (temps d'événement) |
| `receivedAt` | date UTC | oui | heure de réception par le collector |
| `powerKw`, `voltage` | décimal | oui | point décimal, indépendant de la locale |
| `temperature`, `currentA`, `frequencyHz`, `state` | décimal / texte | non | champ **absent** si non fourni (pas de chaîne vide) |

```
XADD energy-measurements MAXLEN ~ 500000 * schemaVersion 1 eventId 7c1f... traceId b1c2... sensorId SENSOR-001 timestamp 2026-09-30T08:15:00.000Z receivedAt 2026-09-30T08:15:00.120Z powerKw 3.72 voltage 226.4 temperature 29.1
```

**Règles de consommation (tous les consommateurs)**

- **Groupes** : `storage-group` et `analytics-group`, créés au démarrage de façon idempotente (`XGROUP CREATE ... 0 MKSTREAM`, erreur BUSYGROUP ignorée).
- **Consumer** : nom `service-hostname` ; lecture `XREADGROUP COUNT 50 BLOCK 2000`.
- **ACK** : `XACK` uniquement après persistance réussie du lot. Une erreur sur un message est journalisée et n'arrête pas le consumer.
- **Reprise** : au démarrage puis toutes les 30 s, `XAUTOCLAIM` des messages inactifs depuis plus de 60 s.
- **Poison / échecs répétés** : structure invalide (champ obligatoire manquant, nombre illisible) → DLQ immédiate ; plus de 5 livraisons → DLQ. DLQ = stream `energy-measurements-dlq` contenant les champs d'origine plus `error`, `failedAt`, `service` ; puis `XACK` de l'original.
- **Rétention** : `MAXLEN ~ 500000` ; alerte si le retard (`lag`) dépasse 50 % de cette valeur.

## 5.4 Contrats de lecture REST

**storage-service (8082)**

| Route | Paramètres | Réponses |
| --- | --- | --- |
| `GET /api/measurements/{sensorId}` | `from`, `to` (plage maximale 7 jours), `limit` (défaut 100, max 1000), `cursor`, `order` (`desc` par défaut ou `asc`) | 200 `{ sensorId, from, to, items[], nextCursor }` ; 400 `RANGE_TOO_LARGE` / `INVALID_PARAMETER`. Capteur sans donnée : 200 avec `items` vide |
| `GET /api/measurements/{sensorId}/latest` | aucun | 200 mesure ; 404 `MEASUREMENT_NOT_FOUND` |

Élément de `items` : `timestamp`, `powerKw`, `voltage`, `temperature`, `currentA`, `frequencyHz`, `state` (champs absents omis). `nextCursor` est un jeton opaque (base64 de jour + dernier `measured_at`) ; `null` en fin de liste.

**sensor-service (8084)**

| Route | Description | Réponses |
| --- | --- | --- |
| `POST /api/sensors` | Créer un capteur | 201 + `Location` ; 400 ; 409 `SENSOR_ALREADY_EXISTS` |
| `GET /api/sensors` | Lister (`page` défaut 0, `size` défaut 20 max 100, filtres `building`, `active`) | 200 `{ items[], page, size, totalElements }` |
| `GET /api/sensors/{id}` | Lire un capteur | 200 ; 404 `SENSOR_NOT_FOUND` |
| `PUT /api/sensors/{id}` | Remplacer (le `sensorId` du corps doit égaler celui de l'URL) | 200 ; 400 ; 404 ; 409 `VERSION_CONFLICT` (champ `version` d'incohérence) |
| `DELETE /api/sensors/{id}` | Désactivation logique (`active` = false) — optionnel | 204 ; 404 |

Corps d'un capteur : `sensorId`, `name`, `building`, `floor`, `maxPowerKw` (supérieur à 0), `active`, `tags[]` ; en réponse s'ajoutent `version`, `createdAt`, `updatedAt`.

**analytics-service (8083)**

| Route | Paramètres | Réponses |
| --- | --- | --- |
| `GET /api/analytics/{sensorId}/summary` | `from`, `to` (max 31 jours ; granularité horaire) | 200 `{ sensorId, effectiveFrom, effectiveTo, count, avgPowerKw, minPowerKw, maxPowerKw, energyKwh, thresholdKw, thresholdExceededCount, anomalyCount }` ; 404 si aucune donnée |
| `GET /api/sensors/{id}/daily-summary` (alias `/api/analytics/{sensorId}/daily-summary`) | `date` (défaut : jour UTC courant) | 200 agrégat quotidien ; 404 |
| `GET /api/analytics/{sensorId}/anomalies` | `from`, `to`, `type` (`THRESHOLD`, `ZSCORE`, `FORECAST_RESIDUAL`), `page`, `size` | 200 liste paginée, plus récentes d'abord |
| `GET /api/analytics/exceedances` | `threshold` (kW, obligatoire), `from`, `to` optionnels | 200 `[ { sensorId, lastPowerKw, maxPowerKw, ... } ]` ; sans `from`/`to` : état courant ; avec : agrégats quotidiens de la période |
| `GET /api/analytics/{sensorId}/forecast` (EXT) | `horizonHours` (1 à 24, défaut 6) | 200 `{ model, generatedAt, points[ {hour, powerKw} ], metrics }` ; 501 `FORECAST_DISABLED` si module inactif |

## 5.5 Contrats internes service-à-service

| Appelant → appelé | Usage | Contrat |
| --- | --- | --- |
| analytics → sensor-service | Récupérer `maxPowerKw` et `active` d'un capteur | `GET /api/sensors/{id}` ; timeout 2 s ; 2 retries ; circuit breaker ; cache Redis `cache:sensor:{id}` 60 s ; repli : `analytics.default-max-power-kw` (10 kW) et marquage `thresholdSource = DEFAULT` dans l'anomalie |
| simulator → collector | Envoi des mesures | Contrat 5.2 ; réessaie sur 503 avec backoff |
| simulator → sensor-service (optionnel) | Enregistrer les capteurs simulés au démarrage (`--register-sensors`) | `POST /api/sensors` ; 409 ignoré |

Aucun appel entre storage-service et analytics-service : ils ne communiquent que par le stream.

## 5.6 Catalogue des codes d'erreur (champ `code` de Problem Details)

`VALIDATION_ERROR`, `MALFORMED_JSON`, `MEASUREMENT_TOO_OLD`, `MEASUREMENT_IN_FUTURE`, `INVALID_PARAMETER`, `RANGE_TOO_LARGE`, `SENSOR_NOT_FOUND`, `SENSOR_ALREADY_EXISTS`, `VERSION_CONFLICT`, `MEASUREMENT_NOT_FOUND`, `BROKER_UNAVAILABLE`, `FORECAST_DISABLED`, `INTERNAL_ERROR`. Les violations de plage temporelle (trop ancien, futur) renvoient 400 pour rester cohérent avec le guide.

---

# 6. Contrats de données

## 6.1 Cassandra (storage-service) — keyspace `smartenergy`

```sql
CREATE KEYSPACE IF NOT EXISTS smartenergy
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS smartenergy.measurements_by_sensor_day (
  sensor_id    text,
  day          date,
  measured_at  timestamp,
  power_kw     double,
  voltage      double,
  temperature  double,
  current_a    double,
  frequency_hz double,
  state        text,
  event_id     uuid,
  PRIMARY KEY ((sensor_id, day), measured_at)
) WITH CLUSTERING ORDER BY (measured_at DESC)
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS',
                    'compaction_window_size': 1};

CREATE TABLE IF NOT EXISTS smartenergy.latest_measurement_by_sensor (
  sensor_id    text PRIMARY KEY,
  measured_at  timestamp,
  power_kw     double,
  voltage      double,
  temperature  double,
  current_a    double,
  frequency_hz double,
  state        text,
  event_id     uuid
);
```

**Règles**

- `day` = date UTC de `measured_at`.
- Écriture : un `INSERT` par table, tous deux idempotents. Pour `latest_measurement_by_sensor`, utiliser `USING TIMESTAMP` égal à `measured_at` en microsecondes (voir D-09).
- Colonnes optionnelles absentes : **non liées** (pas de null explicite).
- Lecture d'historique : une requête par jour de la plage (`sensor_id = ? AND day = ? AND measured_at >= ? AND measured_at < ?`), exécutées en asynchrone puis fusionnées ; jamais de `IN` sur la clé de partition.
- Réplication 1 et niveau de cohérence `LOCAL_ONE` en développement ; en production : facteur 3 et `LOCAL_QUORUM`. TTL : aucun en MVP ; rétention recommandée 365 jours (`default_time_to_live`).
- Volume de référence : 5 capteurs à 1 mesure toutes les 2 s, soit environ 216 000 lignes par jour.

## 6.2 MongoDB `sensor_db` (sensor-service)

Collection `sensors` — index unique sur `sensorId`, index sur `building`.

```json
{
  "sensorId": "SENSOR-001",
  "name": "Compteur salle serveur",
  "building": "Bâtiment A",
  "floor": 1,
  "maxPowerKw": 8.0,
  "active": true,
  "tags": ["critical", "server-room"],
  "version": 0,
  "createdAt": "2026-09-30T08:00:00Z",
  "updatedAt": "2026-09-30T08:00:00Z"
}
```

`version` sert de verrou optimiste (`@Version`) : un `PUT` basé sur une version périmée renvoie 409.

## 6.3 MongoDB `analytics_db` (analytics-service)

| Collection | Contenu et clé | Index |
| --- | --- | --- |
| `sensor_state` | Un document par capteur : `lastMeasuredAt`, `lastPowerKw`, `lastEnergyBasisTs`, statistiques z-score (`n`, `mean`, `m2`) | `sensorId` unique ; `lastPowerKw` (pour `exceedances` courant) |
| `hourly_aggregates` | `_id` = `sensorId|yyyy-MM-ddTHH` ; `count`, `sumPowerKw`, `minPowerKw`, `maxPowerKw`, `energyKwh`, `thresholdExceededCount`, `anomalyCount`, `updatedAt`. La moyenne = `sumPowerKw / count` calculée à la lecture | `(sensorId, hour)` |
| `daily_aggregates` | Mêmes champs, `_id` = `sensorId|yyyy-MM-dd`, champ `date` | `(sensorId, date)` unique ; `maxPowerKw` (exceedances sur période) |
| `anomalies` | `anomalyId`, `sensorId`, `timestamp`, `eventId`, `type`, `severity`, `powerKw`, `thresholdKw`, `thresholdSource`, `zScore`, `detectedAt` | `(eventId, type)` unique (idempotence) ; `(sensorId, timestamp)` ; TTL optionnel 90 jours |
| `forecasts` (EXT) | `sensorId`, `generatedAt`, `model`, `horizonHours`, `points[]`, `metrics` | `(sensorId, generatedAt)` |

Exemple d'agrégat quotidien :

```json
{
  "sensorId": "SENSOR-001",
  "date": "2026-09-30",
  "count": 43012,
  "avgPowerKw": 3.42,
  "minPowerKw": 1.9,
  "maxPowerKw": 7.81,
  "energyKwh": 68.2,
  "thresholdExceededCount": 2,
  "anomalyCount": 2
}
```

## 6.4 Clés Redis

| Clé | Type | Propriétaire | TTL | Rôle |
| --- | --- | --- | --- | --- |
| `energy-measurements` | Stream | collector (écriture) | MAXLEN ~ 500000 | Transit des mesures |
| `energy-measurements-dlq` | Stream | consommateurs | aucun (purge manuelle) | Messages en échec |
| `cache:sensor:{sensorId}` | String JSON | analytics | 60 s | Cache du seuil capteur |
| `cache:latest:{sensorId}` | String JSON | storage (optionnel) | 30 s | Cache-aside de la dernière mesure |
| `dedup:analytics:{eventId}` | String | analytics | 24 h | Déduplication des événements |

Convention : `{usage}:{service}:{identifiant}`.

## 6.5 Règles de calcul (analytics-service)

- **Énergie** (rectangle à gauche) : pour une mesure à l'instant t, `E += P(précédente) × Δt` avec `Δt` en heures = écart réel avec la mesure précédente du capteur, **plafonné à 5 fois l'intervalle attendu** (paramètre) pour ne pas compter les trous de données. Une mesure dont le timestamp est inférieur ou égal au précédent (rejeu ou retard) met à jour `count`/`min`/`max` mais **pas** l'énergie. *Limite documentée* : un fort désordre temporel fausse légèrement l'énergie ; un recalcul nocturne depuis Cassandra est une amélioration possible.
- **Dépassement de seuil** : anomalie `THRESHOLD` si `powerKw` strictement supérieur à `maxPowerKw` du capteur (seuil par défaut 10 kW si capteur inconnu).
- **Score z** (algorithme de Welford, `n`, `mean`, `m2` dans `sensor_state`) : `z = (P − µ) / σ` avec `σ = racine(m2 / (n − 1))`. Évalué seulement quand `n ≥ 30` ; anomalie `ZSCORE` si `|z| > 3` (paramètres). Les mesures déjà anormales **ne mettent pas à jour** les statistiques, pour ne pas fausser µ et σ. *Limite* : un profil sinusoïdal gonfle σ ; une variante par tranche horaire est une amélioration.
- **Sévérité** : `THRESHOLD` = `HIGH`, `ZSCORE` = `MEDIUM` (configurable).
- **Idempotence analytics** : vérifier `dedup:analytics:{eventId}` avant traitement, le marquer **après** écriture Mongo réussie. Une panne dans cette fenêtre peut compter une mesure deux fois (biais négligeable, documenté). L'insertion d'anomalie est idempotente grâce à l'index unique `(eventId, type)`.
- **Prévision (EXT)** : série horaire des moyennes de `hourly_aggregates` ; fenêtre d'entrée 24 h, horizon 1 à 24 h ; valeurs manquantes par interpolation linéaire ; normalisation min-max calculée sur l'entraînement uniquement ; découpage chronologique (jamais aléatoire) entraînement / validation / test ; métriques MAE et RMSE comparées à la baseline.

---

# 7. Spécification par service

## 7.1 simulator

- **Rôle** : source de données contrôlée (EF-01). Java, Python ou JS au choix de l'équipe.
- **Modèle** : `P(t) = Pbase + A·sin(2πt/T) + ε`. Valeurs de départ : `Pbase` 3,5 kW, `A` 1,0 kW, bruit gaussien d'écart-type 0,1, `T` configurable en secondes (**compressée**, par exemple 120 s) pour voir un cycle pendant un test de 2 minutes. Tension autour de 230 V (± 3), température autour de 25 °C.
- **Paramètres** : `--target-url`, `--sensors` (défaut 5), `--interval` (défaut 2 s), `--duration`, `--seed` (reproductibilité), `--register-sensors`.
- **Scénarios injectables** : `--anomaly-sensor SENSOR-003 --anomaly-at 60s --anomaly-duration 10s --anomaly-factor 3` (le seuil `maxPowerKw` doit être inférieur à 3 × `Pbase`, ex. 8 kW) ; `--invalid-rate 0.05` (mesures invalides pour le scénario 2) ; `--duplicate-rate 0.02` (doublons).
- **Sortie** : compteurs envoyés / acceptés (202) / rejetés (400) / erreurs, affichés en fin d'exécution.
- **Validation** : 5 capteurs pendant au moins 2 minutes ; nombre reçu cohérent avec le nombre envoyé.

## 7.2 collector-service

- **Entrées** : contrat 5.2. **Sorties** : XADD (contrat 5.3).
- **Comportement** : valider → calculer `eventId` → ajouter `receivedAt` et `traceId` → `XADD` avec `MAXLEN ~` → répondre 202 → journaliser `sensorId`, `eventId`, id du message Redis. Aucune requête invalide ne crée d'entrée. Redis KO : 503.
- **Pas de base propre.** Ne vérifie pas l'existence du capteur (voir section 3.1).
- **Métriques** : `energy_measurements_received_total{result}`, `energy_measurements_published_total`, durée de publication.
- **Tests clés** : valide → 1 entrée dans le stream ; invalide → 0 ; Redis arrêté → 503 ; doublon → 202.

## 7.3 storage-service

- **Entrées** : stream via `storage-group`. **Sorties** : tables Cassandra ; API de lecture 5.4.
- **Comportement** : lecture par lots → validation de structure → écriture des deux tables → `XACK` du lot réussi → erreurs individuelles vers journal / DLQ sans arrêter le consumer. Reprise `XAUTOCLAIM` au démarrage.
- **Idempotence** : clé primaire naturelle (rejouer écrase la même ligne avec les mêmes valeurs).
- **Métriques** : `energy_persist_duration_seconds`, `energy_persist_errors_total`, `energy_stream_pending`, `energy_stream_lag`.
- **Tests clés** : mesure envoyée → retrouvée via l'API ; service arrêté 1 minute → toutes les mesures retrouvées au redémarrage ; même message deux fois → une seule ligne ; mesure en retard n'écrase pas `latest`.

## 7.4 sensor-service

- **Entrées** : API 5.4. **Données** : `sensor_db.sensors`.
- **Comportement** : CRUD avec validation (`maxPowerKw` supérieur à 0, `sensorId` conforme au regex et immuable), verrou optimiste, désactivation logique.
- **Tests clés** : création puis lecture ; doublon → 409 ; `PUT` avec version périmée → 409 ; métadonnées consultables Cassandra arrêtée (indépendance, critère de l'étape 6).

## 7.5 analytics-service

- **Entrées** : stream via `analytics-group` ; REST vers sensor-service. **Données** : `analytics_db`.
- **Comportement par lot** : dédupliquer → récupérer les seuils (cache) → évaluer les règles de 6.5 → mettre à jour `sensor_state`, `hourly_aggregates`, `daily_aggregates` → insérer les anomalies → `XACK`.
- **Prévision (EXT)** : endpoint actif seulement si `analytics.forecast.enabled=true` ; baseline toujours disponible ; modèle ONNX chargé depuis `models/`.
- **Métriques** : `energy_anomalies_detected_total{type}`, `energy_forecast_latency_seconds`.
- **Tests clés** : calculs d'énergie, de moyenne et de z-score en test unitaire sur jeu connu ; pic ×3 injecté → anomalie `THRESHOLD` visible via l'API ; nombre de mesures de `summary` = nombre dans Cassandra pour la même période.

## 7.6 gateway-service (EXT)

- Spring Cloud Gateway sur `:8080`. Routes : `POST /api/measurements` → collector ; `GET /api/measurements/**` → storage ; `/api/sensors/*/daily-summary` → analytics (règle placée **avant**) ; `/api/sensors/**` → sensor-service ; `/api/analytics/**` → analytics.
- JWT (rôles `ADMIN` écriture capteurs, `VIEWER` lecture), CORS, Swagger agrégé, limitation de débit.

---

# 8. Exigences non fonctionnelles (cibles proposées)

| Domaine | Exigence |
| --- | --- |
| Débit | MVP nominal : 2,5 mesures/s (5 capteurs). Cible de charge : au moins 200 mesures/s soutenues sur un poste de développement |
| Latence collector | p95 inférieur à 100 ms pour un POST |
| Latence de bout en bout | p95 inférieur à 5 s entre réception et disponibilité dans Cassandra |
| Lecture | historique d'une journée avec `limit` 1000 : p95 inférieur à 300 ms ; `latest` : p95 inférieur à 50 ms |
| Fiabilité | Zéro perte de mesure acceptée (202) pour un arrêt d'un consommateur d'au plus 30 minutes ; livraison at-least-once |
| Disponibilité partielle | Collector reste utilisable si Storage ou Analytics est arrêté ; Analytics continue si sensor-service est arrêté |
| Portabilité | Démarrage sur une autre machine avec JDK 17, Docker, une à trois commandes documentées |
| Maintenabilité | Couverture de tests des règles métier ; dépôt compilable sans modification manuelle ; versions de dépendances figées dans un POM parent |
| Données | Horodatage en UTC partout ; aucune donnée personnelle collectée |

---

# 9. Observabilité

- **Actuator** sur tous les services : `health` (groupes `liveness` et `readiness`, indicateurs Redis / Mongo / Cassandra selon le service), `info`, `metrics`, `prometheus` (EXT).
- **Route `GET /api/health`** : 200 `{ status, service, time }` (étape 0 du guide).
- **Métriques minimales** (au moins 3 doivent être montrées en démo) :

| Métrique | Service |
| --- | --- |
| `energy_measurements_received_total{result=accepted|rejected}` | collector |
| `energy_stream_pending`, `energy_stream_lag` (par groupe) | storage, analytics |
| `energy_persist_duration_seconds`, `energy_persist_errors_total` | storage |
| `energy_anomalies_detected_total{type}` | analytics |
| `energy_forecast_latency_seconds` | analytics (EXT) |
- **Logs** : JSON structuré, champs `traceId`, `sensorId`, `eventId`, `service`.
- **Expérience de charge** : monter progressivement le débit du simulateur et commenter latence, retard du stream et erreurs.

---

# 10. Sécurité

**MVP**

- Aucun secret dans Git : fichier `.env` ignoré, `.env.example` fourni.
- Variables d'environnement pour mots de passe et URL ; profils `dev`, `docker`, `prod`.
- Mots de passe pour Redis (`requirepass`) et MongoDB (utilisateurs applicatifs distincts) ; ports des bases **non publiés** hors développement (fichier `docker-compose.override.yml` pour le développement uniquement).
- Validation stricte des entrées (section 5.2), limite de taille du corps, messages d'erreur sans détail interne.
- Note de sécurité en livrable : secrets, accès exposés, endpoints sensibles (écriture capteurs, collecte).

**EXT** : Spring Security + JWT, rôles `ADMIN` / `VIEWER`, clé d'API pour le collector, HTTPS via reverse proxy, OAuth2.

---

# 11. Déploiement Docker

**Images** : versions figées (`redis:7`, `mongo:7`, `cassandra:5`, `eclipse-temurin:17-jre`), Dockerfile multi-étapes (build Maven puis JRE), utilisateur non root.

**Points obligatoires du Compose**

- `healthcheck` sur Redis (`redis-cli ping`), Mongo (`mongosh --eval "db.adminCommand('ping')"`), Cassandra (`cqlsh -e "describe keyspaces"`, `start_period` 60 s, plusieurs essais).
- Service one-shot `cassandra-init` : exécute `scripts/init-cassandra.cql` après que Cassandra soit saine, `restart: "no"`.
- Services applicatifs : `depends_on` avec `condition: service_healthy` ; `restart: unless-stopped`.
- Volumes nommés : `mongo_data`, `cassandra_data`, `redis_data` (AOF).
- Mémoire de Cassandra limitée (`MAX_HEAP_SIZE`, `HEAP_NEWSIZE`) pour tenir sur un portable.
- Redis démarré avec `--appendonly yes --appendfsync everysec`.

```yaml
cassandra-init:
  image: cassandra:5
  depends_on:
    cassandra: { condition: service_healthy }
  volumes: ["./scripts/init-cassandra.cql:/init.cql:ro"]
  command: ["cqlsh", "cassandra", "-f", "/init.cql"]
  restart: "no"
```

**Variables d'environnement principales**

| Variable | Services | Exemple |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | tous | `docker` |
| `SPRING_DATA_REDIS_HOST` / `PORT` / `PASSWORD` | collector, storage, analytics | `redis` / `6379` / (secret) |
| `SPRING_CASSANDRA_CONTACT_POINTS` / `LOCAL_DATACENTER` / `KEYSPACE_NAME` | storage | `cassandra:9042` / `datacenter1` / `smartenergy` |
| `SPRING_DATA_MONGODB_URI` | sensor, analytics | `mongodb://app:(secret)@mongo:27017/sensor_db` |
| `MEASUREMENT_MAX_AGE` / `MEASUREMENT_MAX_FUTURE` | collector | `PT24H` / `PT5M` |
| `STREAM_NAME` / `STREAM_MAXLEN` / `STREAM_MAX_DELIVERIES` | collector, storage, analytics | `energy-measurements` / `500000` / `5` |
| `ANALYTICS_DEFAULT_MAX_POWER_KW` / `ANALYTICS_ZSCORE_THRESHOLD` / `ANALYTICS_FORECAST_ENABLED` | analytics | `10` / `3` / `false` |
| `SENSOR_SERVICE_URL` | analytics | `http://sensor-service:8080` |

**Démarrage cible** : `./mvnw -q package -DskipTests` puis `docker compose up -d --build`, puis `docker compose run --rm simulator`.

---

# 12. Stratégie de tests et recette

## 12.1 Pyramide

- **Unitaires** : validation DTO, calcul d'énergie, seuil, score z, conversions DTO ↔ entités.
- **Intégration (Testcontainers)** : API → Redis ; Redis → storage ; storage → Cassandra ; analytics → Mongo ; lecture via API.
- **Contrat** : l'événement publié par le collector est validé contre `contracts/measurement-event.schema.json` ; chaque service vérifie que son OpenAPI généré correspond à `contracts/*.openapi.yaml`.
- **Bout en bout** : script `scripts/e2e.sh` rejouant les scénarios ci-dessous contre le Compose.

## 12.2 Matrice de recette (reprend le tableau du guide et l'étend)

| N° | Scénario | Résultat attendu | Preuve |
| --- | --- | --- | --- |
| 1 | Mesure valide | HTTP 202 et entrée dans le stream | réponse + `XLEN` |
| 2 | Mesure sans `sensorId` | HTTP 400 Problem Details, aucune entrée | réponse + `XLEN` inchangé |
| 3 | Historique d'un capteur | Liste ordonnée, paginée, bornée par `from`/`to` | appel API |
| 4 | Injection d'un pic ×3 | Anomalie `THRESHOLD` listée | `/anomalies` |
| 5 | Arrêt de storage-service pendant l'envoi | Messages conservés dans Redis (`pending`/`lag` en hausse) | `XINFO GROUPS` |
| 6 | Redémarrage de storage-service | Reprise du traitement, aucune mesure perdue (comptage Cassandra = comptage envoyé accepté) | comptage |
| 7 | Redémarrage complet de la plateforme | Données toujours présentes (volumes) | appels API |
| 8 | Doublon | Une seule ligne Cassandra, compteur analytics inchangé | API + agrégat |
| 9 | Redis arrêté | Collector répond 503 | réponse |
| 10 | Message poison dans le stream | Envoyé en DLQ, le flux continue | `XLEN` DLQ |
| 11 | sensor-service arrêté | Analytics traite avec le seuil par défaut | anomalie `thresholdSource = DEFAULT` |

---

# 13. Plan de réalisation

| Jour | Travaux (conformes au plan du guide) | Livrables de ce cahier des charges |
| --- | --- | --- |
| J1 | Git, architecture, contrat JSON | `contracts/` (schéma événement, OpenAPI squelettes), ADR, README |
| J2 | Simulateur + Collector | Contrat 5.2 validé, tests 1 et 2 |
| J3 | Redis Streams + consumer | Groupes, ACK, reprise, DLQ ; tests 5 et 6 |
| J4 | Cassandra + MongoDB | CQL d'init, sensor-service, persistance démontrée |
| J5 | API de consultation + Swagger | Routes 5.4 documentées |
| J6 | Analytics descriptif + anomalies | Règles 6.5, tests 4 et 8 |
| J7 | Docker Compose + tests d'intégration | MVP exécutable ; tests 7, 9, 10, 11 |
| J8 | IA / sécurité / monitoring | Extension choisie et documentée |
| J9 | Tests finaux, rapport | Matrice de recette complétée |
| J10 | Soutenance | Démonstration de bout en bout |

**Répartition suggérée (4 étudiants, sans silos, revues croisées)** : A collector + simulateur + producer ; B storage + Cassandra ; C sensor-service + API de consultation + Swagger ; D analytics + tests + monitoring.

**Définition de « terminé » pour une étape** : code mergé, tests passants, contrat à jour, README mis à jour, preuve de test ajoutée au tableau de recette.

---

# 14. Risques et points de veille à vérifier

Ces points reposent sur ma connaissance des technologies et **n'ont pas été vérifiés dans la documentation en ligne** ; à contrôler au moment de l'implémentation.

- **Versions** : figer la version de Spring Boot (et Java 17 minimum) dans un POM parent ; vérifier la compatibilité de Spring Data Redis / Cassandra / MongoDB avec cette version.
- **API Redis Streams côté Spring** : choisir entre `StreamMessageListenerContainer` et une boucle `XREADGROUP` explicite ; la boucle explicite facilite `XAUTOCLAIM`, le comptage de livraisons et la DLQ.
- **Démarrage de Cassandra 5 en Docker** : lent et gourmand en mémoire ; prévoir healthcheck, limite de heap et patience dans les scripts de test.
- **Spring Data Cassandra** : `local-datacenter` obligatoire (`datacenter1` pour l'image officielle) ; ne pas laisser Spring créer le schéma.
- **DJL + ONNX** : l'exemple du guide est simplifié ; le chargement d'un `.onnx` demande le moteur ONNX Runtime de DJL et un `Translator` adapté. Alternative plus directe : ONNX Runtime Java.
- **Mesures hors ordre et horloges** : un capteur à l'horloge décalée de moins de 5 minutes dans le futur peut retarder la mise à jour de `latest` (conséquence de D-09) ; acceptable, à mentionner.
- **Alertes en rafale** : un pic de 10 secondes produit une anomalie par mesure ; le regroupement en « épisodes » est une amélioration.
- **Débit du stream** : surveiller la mémoire Redis ; si la charge visée dépasse largement la cible, évaluer Kafka (extension).
- **Portée du projet** : ne pas démarrer les extensions avant la validation du MVP (J7), conformément au guide.

---

# 15. Questions ouvertes à valider avec l'enseignant

1. **Agrégats MongoDB** : acceptés quand ils sont produits par analytics-service (D-02) plutôt que par storage-service comme à l'étape 7 ? Repli possible : storage met à jour un compteur d'agrégat simple.
2. **sensor-service séparé** (D-01) ou module de analytics-service pour réduire le nombre de services ?
3. **`/api/sensors/{id}/daily-summary`** servi par analytics via alias (D-17) : acceptable pour la validation de l'étape 6 ?
4. **Granularité horaire** de `summary` (D-13) : suffisante pour le critère « moyenne entre deux dates » ?
5. **Mesures de plus de 24 h** : rejet (400) acceptable, ou file de rattrapage souhaitée ?
6. **Extension prioritaire** pour J8 : prévision IA, Prometheus/Grafana ou JWT ?

---

# 16. Traçabilité guide → cahier des charges

| Étape du guide | Sections / exigences |
| --- | --- |
| Étape 0 — Environnement | 8, 9 (`/api/health`), 11 |
| Étape 1 — Contrat de données | 5.1, 5.2, 5.3, 5.6 (EF-02) |
| Étape 2 — Simulateur | 7.1 (EF-01) |
| Étape 3 — Collector | 5.2, 7.2 (EF-02, EF-03) |
| Étape 4 — Redis Streams | 5.3, 6.4, D-04 à D-07 (EF-03, EF-15) |
| Étape 5 — Cassandra | 6.1, D-08 à D-11 (EF-04) |
| Étape 6 — MongoDB | 6.2, 6.3, 7.4 (EF-05, EF-09) |
| Étape 7 — Storage Service | 5.3, 7.3, D-02, D-07 (EF-04, EF-15) |
| Étape 8 — API de consultation | 5.4 (EF-06, EF-07, EF-17) |
| Étape 9 — Analytics | 6.5, 7.5 (EF-08, EF-10 à EF-13) |
| Étape 10 — Docker | 11 (EF-16) |
| Étape 11 — Observabilité | 9 (EF-14) |
| Étape 12 — Sécurité | 10 (EF-18) |
| Stratégie de tests | 12 (scénarios 1 à 7 du guide = lignes 1 à 7 de la recette) |

---

# 17. Glossaire

- **Consumer group** : ensemble de consommateurs se partageant les messages d'un stream ; chaque groupe reçoit tous les messages.
- **ACK** : accusé de traitement ; tant qu'il n'est pas envoyé, le message reste *pending*.
- **DLQ** : Dead Letter Queue, stream des messages que l'on n'arrive pas à traiter.
- **Idempotence** : traiter deux fois un message donne le même résultat que le traiter une fois.
- **At-least-once** : un message est livré une fois ou plus, jamais zéro.
- **Clé de partition / clustering (Cassandra)** : la première répartit les données entre nœuds ; la seconde ordonne les lignes dans la partition.
- **TWCS** : stratégie de compaction par fenêtres de temps, adaptée aux séries temporelles.
- **Temps d'événement** : moment où la mesure a eu lieu (`timestamp`), à distinguer du moment de réception (`receivedAt`).
- **Problem Details (RFC 9457)** : format standard JSON des erreurs HTTP.