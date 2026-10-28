# Carte des tâches restantes — MVP puis extensions

Deux parties, dans l'ordre d'exécution :

- **A. MVP** : ce qui reste à coder ou à écrire pour finir le périmètre obligatoire.
- **B. Extensions (hors MVP)** : une sous-section par identifiant d'extension, calée sur le CDC §18
  (`EXT-9C`, `EXT-11`, `EXT-12`, `EXT-GW`, `EXT-B`). **Ne pas commencer avant la validation du MVP (J7).**

Les lignes marquées **[TODO code]** proviennent des en-têtes `TODO(...)` du code
(`make todo` donne la même liste en ligne de commande) : chaque fichier contient le guide/CDC de
référence, les étapes à suivre, les pièges, les tests attendus, puis un **squelette commenté** à
décommenter (`Ctrl+/`) et à compléter. Les lignes **[manuelles]** sont écrites à la main et ne
figurent pas dans `make todo`.

Ordre conseillé : A de haut en bas, puis B après J7. Les classes déjà opérationnelles (contrats,
plomberie web, sensor-service, simulateur, persistance storage des étapes 5 et 7, API de
consultation de l'étape 8, limite de taille du corps de l'étape 12, scripts de base de données,
Docker) ne figurent pas ici.

---

## A. MVP — reste à faire

### Tests manquants — `sensor-service`  _(plan : au fil de l'eau)_

**Fait le 28/10/2026** : les trois tests annoncés ci-dessous sont écrits et verts (voir `docs/tests.md`).

| Fichier / cible | État |
|---|---|
| `sensor-service/src/test/.../SensorServiceTest.java` | **OK** — `PUT` avec `version` périmée → 409 `VERSION_CONFLICT` (+ version courante acceptée) |
| `sensor-service/src/test/.../SensorServiceTest.java` | **OK** — `DuplicateKeyException` (course sur l'index unique) → 409 `SENSOR_ALREADY_EXISTS` |
| `sensor-service/src/test/.../SensorServiceIntegrationTest.java` | **OK** — intégration Testcontainers MongoDB : création puis lecture (201 + `Location`, filtre `building`) ; métadonnées consultables avec Cassandra arrêté (critère étape 6) ; doublon → 409 + index `uk_sensorId` vérifié |

**Total A : aucun code MVP en attente — 0 test à écrire (3/3 livrés).**

### Tests d'intégration et unitaires livrés  _(pour mémoire)_

Tests d'intégration **livrés** (Testcontainers, exécutés à chaque `mvn test`) :
`CollectorIntegrationTest` (6 tests, scénarios 1, 2, 8, 9),
`StorageIntegrationTest` (5 tests, scénarios 3, 5, 6, 8),
`AnalyticsIntegrationTest` (4 tests, scénarios 4, 8, 11, avec faux sensor-service HTTP),
`SensorServiceIntegrationTest` (3 tests, critère étape 6 — création/lecture, lecture sans
Cassandra, doublon → 409).
Recette de bout en bout : `make e2e` (scénarios 1–11 + 413, 64/64 OK — `docs/tests.md`).

---

## B. Extensions (hors MVP) — par étape

Chaque sous-section reprend le gabarit du CDC §18 : *État → Exigences → Fichiers → Activation → Critère → Renvois*.
Les identifiants sont ceux du CDC §18 et du README racine §10.2.

### Étape 9C · EXT-9C — Prévision IA  _(plan : J8)_

- **État** : squelette livré, code non écrit — endpoint en 501 `FORECAST_DISABLED`.
- **Exigences** : EF-13, CDC §6.5, D-18.

| Fichier | À faire |
|---|---|
| `analytics-service/src/main/java/com/smartenergy/analytics/forecast/ForecastService.java` | **[TODO code]** [EXTENSION] Prevision : baseline puis modele ONNX |
| `ml/train_forecast.py` | **[manuelles]** pipeline complet : préparation, découpage chronologique, baselines (MAE/RMSE), export ONNX — voir `ml/README.md` |
| `models/energy.onnx` | **[manuelles]** modèle entraîné et exporté (monté en lecture seule dans analytics) |

- **Activation** : `ANALYTICS_FORECAST_ENABLED=true` + `smartenergy.analytics.forecast.model-path`.
- **Critère** : MAE/RMSE < baseline ; `GET /api/analytics/{id}/forecast` → 200 avec `points[]`.
- **Renvois** : CDC §18.1 · `analytics-service/README.md` (§ Extension) · `ml/README.md` · `models/README.md` · README racine §10.2.

### Étape 11 · EXT-11 — Observabilité avancée : Prometheus / Grafana

- **État** : aucun fichier. Les endpoints `/actuator/prometheus` sont déjà exposés (MVP).
- **Exigences** : EF-14 (volet dashboard), CDC §9.

| Fichier | À faire |
|---|---|
| _(aucun fichier encore)_ | **[manuelles]** service Compose `prometheus` + `grafana` (profil dédié), provisioning des datasources, dashboard avec ≥ 3 métriques CDC §9, restriction d'Actuator en production (`docs/security.md`) |

- **Activation** : profil Compose (ex. `--profile monitoring`).
- **Critère** : dashboard Grafana affichant ≥ 3 métriques de §9 ; les 4 `/actuator/prometheus` → 200.
- **Renvois** : CDC §18.2 · `docs/security.md` · README racine §10.2.

### Étape 12 · EXT-12 — Sécurité avancée : JWT, rôles, clé d'API

- **État** : aucun fichier. Volet MVP de l'étape 12 livré (secrets, validation, limite 10 Ko).
- **Exigences** : EF-18 (volet EXT), CDC §10.

| Fichier | À faire |
|---|---|
| _(aucun fichier encore)_ | **[manuelles]** Spring Security + JWT (rôles `ADMIN`/`VIEWER`) sur les services REST, clé d'API pour `POST /api/measurements`, TLS via reverse proxy, éventuellement OAuth2 ; adapter `make e2e` |

- **Activation** : dépendances + variables d'environnement (secrets hors Git).
- **Critère** : écriture capteurs refusée sans token `ADMIN` ; collecte refusée sans clé d'API ; `make e2e` vert.
- **Renvois** : CDC §18.3 · `docs/security.md` · README `collector-service` et `sensor-service`.

### Étapes 10/12 · EXT-GW — API Gateway

- **État** : scaffolding livré (`.example` uniquement), non codé.
- **Exigences** : CDC §7.6, D-16, D-17.

| Fichier | À faire |
|---|---|
| `gateway-service/pom.xml.example` | **[manuelles]** renommer en `pom.xml`, version de Spring Cloud compatible, module ajouté au pom parent |
| `gateway-service/routes.yml.example` | **[manuelles]** recopier dans `application.yml` (ordre des règles respecté), service Compose profil `gateway` sur `:8080`, retrait des ports 8081–8084 |

- **Activation** : profil Compose `gateway` ; couplage naturel avec EXT-12.
- **Critère** : les 5 routes répondent via `:8080` avec l'ordre du tableau §7.6, Swagger agrégé.
- **Renvois** : CDC §18.4 · `gateway-service/README.md` · README racine §10.2.

### Bonus · EXT-B — Évolutivité et valorisation

- **État** : aucun fichier (réflexion seulement). À activer sur signaux (lag du stream, demande d'alertes…).

| Fichier | À faire |
|---|---|
| _(aucun fichier encore)_ | **[manuelles]** Kafka (D-03), Kubernetes, interface Web, alertes en « épisodes » (CDC §14), améliorations analytiques de §6.5 (recalcul nocturne, z-score horaire), OAuth2 |

- **Renvois** : CDC §18.5 · `docs/decisions.md` · README racine §10.2.

---

**Total général** :
**A (MVP)** : **terminé** — 0 test en attente (les 3 tests `sensor-service` sont livrés).
**B (extensions)** : `EXT-9C` → 1 fichier de code à décommenter (`ForecastService`) + 2 fichiers à
produire (`ml/train_forecast.py`, `models/energy.onnx`) ; `EXT-GW` → 2 fichiers `.example` à
finaliser ; `EXT-11`, `EXT-12`, `EXT-B` → aucun fichier, tâches manuelles à créer.

**Règle d'alignement** : toute extension démarrée ou livré(e) met à jour, dans le même commit,
`docs/cdc.md` §18, ce fichier, le README racine (§10.2 / §11) et le README du service concerné.
