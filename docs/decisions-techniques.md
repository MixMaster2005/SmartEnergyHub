# Décisions techniques (ADR) — SmartEnergy Hub

Format court : contexte → décision → justification → conséquences.

## ADR-01 — Trois services applicatifs plutôt que quatre
- **Contexte** : le guide décrit simulateur, Collector, Storage et API/Analytics.
- **Décision** : regrouper API de consultation + calculs descriptifs dans un seul
  `analytics-service`.
- **Justification** : périmètre MVP pour 4 personnes ; moins d'intégration.
- **Conséquences** : `analytics-service` porte à la fois lecture et calculs ; reste
  aligné sur le diagramme logique.

## ADR-02 — Redis Streams + consumer group `storage-group`
- **Décision** : `XADD`/`XREADGROUP`/`XACK` avec `eventId` unique.
- **Justification** : découplage collecte/stockage, reprise après panne (EF-15, T10),
  idempotence (RG-06).
- **Conséquences** : accusé uniquement après persistance ; gestion des messages en attente.

## ADR-03 — Cassandra partitionné par `(sensor_id, day)`
- **Décision** : table `measurements_by_sensor_day`, clustering `measured_at DESC`, puis `event_id`.
- **Justification** : modélisation guidée par les requêtes d'historique ; évite les
  partitions géantes.
- **Conséquences** : pas de lecture globale sans connaître capteur/jour ; `event_id`
  ajouté pour lever les collisions d'horodatage.

## ADR-04 — MongoDB pour métadonnées et agrégats
- **Décision** : collections `sensors` (avec `maxPowerKw`) et `aggregates`.
- **Justification** : documents lus ensemble ; seuil métier pour la détection.
- **Conséquences** : index unique sur `sensorId` ; agrégats écrits par le Storage.

## ADR-05 — Spring Boot 4.1.1 + Java 17 + Maven
- **Contexte** : start.spring.io ne propose plus que la branche 4.x (2026).
- **Décision** : Java 17 (installé), Maven, Boot 4.1.1 ; starters renommés
  (`spring-boot-starter-webmvc`).
- **Justification** : version courante supportée par l'outil officiel.
- **Conséquences** : propriétés Boot 4 (`spring.data.redis.*`, `spring.data.cassandra.*`,
  `spring.mongodb.*`) ; `springdoc-openapi` **3.1.1** pour Swagger.

## ADR-06 — Simulateur en Python
- **Décision** : script Python `requests`, configuration par variables d'environnement.
- **Justification** : léger (128 Mo), autorisé par le guide, rapide à conteneuriser.
- **Conséquences** : dépendance `requests` figée ; build image minimal.

## ADR-07 — Deux profils Docker Compose
- **Décision** : profil par défaut = bases seules ; `--profile app` = stack complète.
- **Justification** : machine 7,2 Go ; éviter de tout charger pendant le développement.
- **Conséquences** : commandes documentées dans le README et le compose.

## ADR-08 — Limitation des ressources par conteneur
- **Décision** : `mem_limit`/`mem_reservation` + heap JVM borné (`MaxRAMPercentage=60`).
- **Justification** : éviter l'OOM sur machine limitée ; Cassandra seul à 1 Go.
- **Conséquences** : performances plafonnées volontairement ; à ajuster en production.

## ADR-09 — Ports exposés en loopback
- **Décision** : `127.0.0.1:<port>:<port>` pour toutes les publications.
- **Justification** : ENF-07, aucune exposition réseau non nécessaire.

## ADR-10 — Secrets de développement uniquement
- **Décision** : identifiants Mongo de dev dans `.env`, jamais de secret réel dans Git.
- **Justification** : ENF-07. `.env.local` et `*.pem` exclus par `.gitignore`.

## Points à valider avec l'enseignant (CDC §16)
1. Durée réelle du projet (15 jours proposés).
2. Unité/sens de `powerKw` et méthode d'estimation en kWh.
3. Valeurs métier de `maxPowerKw`.
4. Nombre/intervalle final des capteurs de démonstration.
5. API hébergée dans `analytics-service` (simplification).
6. Versions d'images Docker figées.
