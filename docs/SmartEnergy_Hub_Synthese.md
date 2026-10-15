# SmartEnergy Hub – Synthèse complète du guide

> Projet tutoré Master 1 MSI – Spring Boot, Big Data & IA (2026), Fabrice RAZAFINDRAIBE.
> Plateforme de supervision énergétique distribuée : **Spring Boot + MongoDB + Redis + Cassandra + Docker (+ IA)**.

---

## 1. Vue d'ensemble

**But** : concevoir une plateforme qui **collecte, stocke, analyse et expose** des données énergétiques (capteurs simulés ou réels).

**Philosophie pédagogique** : l'objectif n'est pas seulement une appli qui marche. Il faut :
- comprendre *pourquoi* chaque technologie est utilisée ;
- construire l'architecture progressivement ;
- tester chaque composant indépendamment ;
- documenter les choix et démontrer la qualité.

**Chaque étape** précise : objectif, tâches, fichiers/composants, tests, livrable, critère de validation.

**Pourquoi pas une seule base ?** Une base unique suffit à petite échelle mais atteint ses limites avec : flux continu de mesures, lectures fréquentes, historiques volumineux, besoin de cache, analyses prédictives, forte disponibilité.

### Compétences visées
1. Architecture orientée services avec Spring Boot
2. Développer/documenter des API REST
3. Justifier l'usage de plusieurs bases NoSQL
4. Manipuler un flux avec Redis Streams
5. Modéliser des données temporelles dans Cassandra
6. Stocker des documents métier dans MongoDB
7. Conteneuriser avec Docker Compose
8. Tests unitaires et d'intégration
9. Exposer des métriques de supervision
10. Intégrer un modèle simple de prévision/détection d'anomalie
11. Présenter choix, résultats et limites

### Objectifs
- **Général** : plateforme distribuée de gestion énergétique intégrant Big Data, IA et DevOps.
- **Spécifiques** :
  - MongoDB → équipements, agrégats, rapports, données documentaires
  - Redis → cache + transit rapide des mesures
  - Cassandra → séries temporelles volumineuses
  - Backend modulaire/microservices Spring Boot
  - Module d'analyse prédictive
  - Résilience, observabilité, évolutivité
  - Déploiement Docker Compose

---

## 2. Périmètre : obligatoire vs optionnel

### Niveau 1 – MVP obligatoire
- Simulateur de capteurs
- Service Spring Boot de collecte
- Redis Streams pour transporter les mesures
- Service de stockage
- Cassandra (mesures temporelles)
- MongoDB (équipements + agrégats)
- API REST de consultation
- Calcul simple de consommation moyenne + détection de dépassement
- Docker Compose
- Documentation Swagger/OpenAPI
- Tests démontrant que la chaîne complète fonctionne

### Niveau 2 – Extensions valorisées (après validation du MVP)
LSTM ou régression (prévision) · API Gateway · authentification JWT · Prometheus + Grafana · gestion automatique des alertes · Kafka (à la place ou en complément de Redis Streams) · Kubernetes · interface Web/mobile.

---

## 3. Scénario fonctionnel

Bâtiments avec plusieurs compteurs/capteurs, chacun envoyant périodiquement une mesure.

**Contenu minimal d'une mesure** : identifiant capteur, date/heure, puissance (kW), tension (V), température ambiante (°C) ; éventuellement courant, fréquence, état de fonctionnement.

```json
{
  "sensorId": "SENSOR-001",
  "timestamp": "2026-09-30T08:15:00Z",
  "powerKw": 3.72,
  "voltage": 226.4,
  "temperature": 29.1
}
```

**Questions auxquelles le système doit répondre** :
- Dernière valeur reçue pour un capteur ?
- Consommation moyenne entre deux dates ?
- Quels capteurs dépassent un seuil ?
- Consommation prévue pour les prochaines heures ?
- Quels événements sont anormaux ?

---

## 4. Architecture

**Flux** :
`Simulateur IoT → Collector Service (Spring Boot) → Redis Streams → Storage Service (Spring Boot) → Cassandra (séries temporelles) + MongoDB (métadonnées/agrégats)`
puis `Analytics Service (règles + IA)` et `API / Dashboard (HTTP)` pour la restitution.

| Composant | Rôle | Question à laquelle il répond |
|---|---|---|
| Simulateur IoT | Générer des mesures réalistes à intervalle régulier | Comment tester sans vrais capteurs ? |
| Collector Service | Valider et recevoir les mesures | Comment protéger le système contre les données invalides ? |
| Redis Streams | Tamponner et distribuer rapidement les événements | Comment découpler réception et stockage ? |
| Storage Service | Consommer les flux et persister | Comment centraliser la logique de persistance ? |
| Cassandra | Stocker de gros volumes de mesures temporelles | Comment lire vite l'historique d'un capteur ? |
| MongoDB | Capteurs, configurations, agrégats, rapports | Comment conserver des documents évolutifs ? |
| Analytics Service | Indicateurs, anomalies, prédictions | Comment transformer des mesures en information utile ? |
| API / Dashboard | Fournir les résultats | Comment rendre les données consultables ? |

---

## 5. Organisation du dépôt Git

```
smartenergy-hub/
|-- README.md
|-- docker-compose.yml
|-- docs/ (architecture.md, api.md, tests.md)
|-- simulator/
|-- collector-service/
|-- storage-service/
|-- analytics-service/
|-- gateway-service/     # optionnel
|-- monitoring/          # optionnel
|-- scripts/ (init-cassandra.cql, sample-data.json)
`-- models/              # modèles IA exportés
```

Le **README** doit expliquer comment lancer le projet, si possible **avec une commande unique**.

---

## 6. Étape 0 – Préparation de l'environnement

**Objectif** : environnement reproductible, identique pour tous les membres.

**Logiciels** : JDK 17 (ou LTS compatible), Maven ou Gradle, Docker + Docker Compose, Git, IDE Java, Postman/Bruno (ou équivalent), navigateur (Swagger UI).

**Travail** :
1. Créer le dépôt Git
2. Créer une branche de développement
3. Générer un projet Spring Boot (Spring Web + Actuator)
4. Créer la route `/api/health` renvoyant un message simple
5. Construire le projet
6. Lancer l'appli et vérifier le port HTTP

- **Livrable** : capture/démo de l'API de santé, dépôt Git initialisé, README avec prérequis.
- **Validation** : la commande de build fonctionne et l'URL de santé répond **HTTP 200**.

---

## 7. Étape 1 – Contrat de données

**Objectif** : définir précisément les données échangées *avant* de coder la logique métier.

DTO minimal :
```java
public record EnergyMeasurementRequest(
    String sensorId, Instant timestamp,
    Double powerKw, Double voltage, Double temperature
) {}
```

Avec Jakarta Validation (identifiant obligatoire, puissance ≥ 0, tension dans une plage plausible…) :
```java
public record EnergyMeasurementRequest(
    @NotBlank String sensorId,
    @NotNull Instant timestamp,
    @NotNull @PositiveOrZero Double powerKw,
    @NotNull @DecimalMin("0.0") Double voltage,
    Double temperature
) {}
```

**Questions à traiter dans le rapport** :
- Pourquoi un horodatage **UTC** ?
- Que faire si un capteur envoie **deux fois la même mesure** ?
- Faut-il accepter une **mesure très ancienne** ?
- Différence entre **erreur de format** et **anomalie énergétique** ?

- **Livrable** : schéma JSON, DTO Java, règles de validation documentées.
- **Validation** : mesure correcte acceptée ; mesure incorrecte → **HTTP 400** avec message compréhensible.

---

## 8. Étape 2 – Simulateur de capteurs

**Objectif** : source de données contrôlée pour tester sans matériel IoT.

- Langage libre : Java, Python ou JavaScript.
- Génère plusieurs capteurs, envoie une mesure toutes les quelques secondes au Collector.

**Formule de génération de la puissance** :

`P_t = P_base + A · sin(2π·t / T) + ε_t`

- `P_base` : consommation moyenne
- `A` : amplitude des variations
- `T` : période
- `ε_t` : bruit aléatoire

Doit pouvoir **injecter volontairement une anomalie** (ex. puissance ×3 pendant une courte période).

**Test** : lancer **5 capteurs pendant au moins 2 minutes** et vérifier le nombre de requêtes reçues.

- **Livrable** : code du simulateur, paramètres configurables, exemple d'exécution.
- **Validation** : génération automatique de données valides + au moins un scénario d'anomalie activable.

---

## 9. Étape 3 – Collector Service

**Objectif** : recevoir, valider et publier dans Redis Streams **sans bloquer l'émetteur**.

```java
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {
    private final MeasurementPublisher publisher;
    public MeasurementController(MeasurementPublisher publisher) { this.publisher = publisher; }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody EnergyMeasurementRequest request) {
        publisher.publish(request);
        return ResponseEntity.accepted().build();
    }
}
```

**Pourquoi HTTP 202 (Accepted) ?** Réception et stockage final sont découplés : le Collector confirme que la donnée est *acceptée pour traitement*, sans garantir qu'elle est déjà écrite dans toutes les bases.

**À réaliser** :
1. Créer le DTO de requête
2. Valider le JSON
3. Créer un service de publication
4. Publier chaque événement dans un stream Redis (ex. `energy-measurements`)
5. Journaliser l'identifiant du capteur et l'identifiant du message Redis
6. Prévoir une gestion d'erreur simple

- **Livrable** : Collector exécutable + collection de tests HTTP.
- **Validation** : requête valide → événement créé dans Redis ; requête invalide → aucun événement.

---

## 10. Étape 4 – Redis Streams

**Objectif** : Redis comme couche de transit rapide entre réception et persistance.

Champs d'un événement : `sensorId`, `timestamp`, `powerKw`, `voltage`, `temperature`.

**Notions à maîtriser** : stream · identifiant d'événement · consumer group · consumer · accusé de traitement (ACK) · message en attente (pending).

**Travail** : créer le consumer group **`storage-group`**. Le Storage Service consomme ce groupe et **n'accuse (ACK) qu'après persistance réussie**.

**Expérience pédagogique** : arrêter volontairement le Storage Service pendant que le simulateur tourne → les événements continuent d'entrer dans Redis → relancer le service et vérifier la reprise du traitement.

- **Livrable** : configuration Redis, consumer group, preuve de reprise après interruption.
- **Validation** : aucune mesure produite pendant l'arrêt ne disparaît dans le scénario de test prévu.

---

## 11. Étape 5 – Cassandra (séries temporelles)

**Objectif** : concevoir la table **à partir des requêtes**, pas comme une base relationnelle.

Requête principale : *« récupérer l'historique d'un capteur entre deux dates »*.

```sql
CREATE KEYSPACE IF NOT EXISTS smartenergy
WITH replication = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

CREATE TABLE IF NOT EXISTS smartenergy.measurements_by_sensor_day (
  sensor_id text,
  day date,
  measured_at timestamp,
  power_kw double,
  voltage double,
  temperature double,
  PRIMARY KEY ((sensor_id, day), measured_at)
) WITH CLUSTERING ORDER BY (measured_at DESC);
```

**Point pédagogique clé** : `(sensor_id, day)` = **clé de partition composite**. Cela évite qu'un capteur très actif accumule toutes ses mesures dans **une seule partition** pendant des années. `measured_at` = clé de clustering (tri décroissant, dernières mesures en premier).

**Travail** :
1. Créer le keyspace
2. Créer la table
3. Implémenter l'entité Spring Data Cassandra
4. Écrire une mesure
5. Lire les mesures d'un capteur pour une journée
6. Mesurer approximativement le temps de réponse sur un jeu de données plus important

- **Livrable** : script CQL, entité Java, repository, test de lecture d'historique.
- **Validation** : le groupe explique le choix de la clé de partition et démontre la lecture temporelle.

---

## 12. Étape 6 – MongoDB (données documentaires)

**Objectif** : stocker ce qui n'a ni la même structure ni le même usage que les mesures brutes.

**Document capteur** :
```json
{
  "sensorId": "SENSOR-001",
  "name": "Compteur salle serveur",
  "building": "Bâtiment A",
  "floor": 1,
  "maxPowerKw": 8.0,
  "active": true,
  "tags": ["critical", "server-room"]
}
```

**Agrégat quotidien** :
```json
{
  "sensorId": "SENSOR-001",
  "date": "2026-09-30",
  "avgPowerKw": 3.42,
  "maxPowerKw": 7.81,
  "energyKwh": 68.2,
  "anomalyCount": 2
}
```

**API minimale** :
- `POST /api/sensors` : créer un capteur
- `GET /api/sensors` : lister
- `GET /api/sensors/{id}` : afficher un capteur
- `PUT /api/sensors/{id}` : modifier
- `GET /api/sensors/{id}/daily-summary` : obtenir un résumé

- **Livrable** : collections MongoDB, repositories, endpoints de consultation.
- **Validation** : métadonnées consultables **indépendamment** des mesures brutes de Cassandra.

---

## 13. Étape 7 – Storage Service

**Objectif** : consommer les messages Redis, les convertir en objets métier, les persister de façon fiable.

**Traitement attendu** :
1. Lire un événement Redis
2. Vérifier sa structure
3. Enregistrer la mesure dans Cassandra
4. Mettre à jour si nécessaire un cache ou un agrégat
5. Accuser (ACK) le message Redis
6. Journaliser les erreurs **sans arrêter tout le consumer**

**Idempotence** : un même événement peut être traité deux fois. Proposer une stratégie : identifiant unique d'événement, contrôle applicatif, ou conception tolérante aux doublons.

- **Livrable** : Storage Service complet (consumer Redis + persistance Cassandra/MongoDB).
- **Validation** : une mesure envoyée au Collector est retrouvée dans Cassandra, et les infos attendues sont mises à jour dans MongoDB.

---

## 14. Étape 8 – API de consultation

**Objectif** : rendre les données exploitables par un utilisateur ou un futur dashboard.

| Endpoint | Méthode | Résultat |
|---|---|---|
| `/api/sensors` | GET | Liste des capteurs |
| `/api/sensors/{id}` | GET | Métadonnées du capteur |
| `/api/measurements/{sensorId}` | GET | Historique filtré par dates |
| `/api/measurements/{sensorId}/latest` | GET | Dernière mesure connue |
| `/api/analytics/{sensorId}/summary` | GET | Moyenne, maximum, énergie estimée |
| `/api/analytics/{sensorId}/anomalies` | GET | Anomalies détectées |
| `/api/analytics/{sensorId}/forecast` | GET | Prévisions (si module IA actif) |

Les paramètres **`from`** et **`to`** limitent les périodes demandées.

**Documentation** : tous les endpoints dans OpenAPI/Swagger, avec exemples de requêtes et réponses.

- **Livrable** : API de lecture documentée et testable depuis Swagger UI.
- **Validation** : un utilisateur ne connaissant pas le code comprend et teste les principales routes grâce à la doc.

---

## 15. Étape 9 – Module Analytics

**Objectif** : transformer les mesures brutes en indicateurs, puis introduire l'IA progressivement.

### Niveau A – Analyse descriptive (obligatoire, avant toute IA)
Calculer : moyenne de puissance · maximum · minimum · énergie estimée · nombre de mesures reçues · nombre de dépassements de seuil.

Énergie (mesures espacées de Δt heures) :
**E ≈ Σᵢ₌₁ⁿ Pᵢ · Δt** (E en kWh si P en kW)

### Niveau B – Détection simple d'anomalie
- **Seuil par capteur** : anomalie si `P_t > P_max`
- **Score standardisé** : `z_t = (P_t − µ) / σ` ; mesure signalée si `|z_t|` dépasse un seuil **choisi et justifié**

### Niveau C – Prévision IA
Après validation des niveaux précédents : régression, réseau dense ou LSTM. Démarche clairement séparée en :
1. Préparation des données
2. Séparation entraînement / validation / test
3. Entraînement
4. Mesure de l'erreur
5. Export du modèle
6. Intégration dans l'application

### Métriques de prévision (au moins une)
- **MAE** = (1/n) Σ |yᵢ − ŷᵢ|
- **RMSE** = √[(1/n) Σ (yᵢ − ŷᵢ)²]

- **Livrable** : service d'analyse avec indicateurs obligatoires ; module IA documenté si réalisé.
- **Validation** : démontrer au minimum un calcul descriptif correct et expliquer les limites de la méthode de détection/prévision choisie.

### Intégration d'un modèle IA avec DJL
Un modèle entraîné hors Spring Boot est exporté (ex. **ONNX**) puis chargé par un service Java :

```java
Criteria<float[], float[]> criteria = Criteria.builder()
    .setTypes(float[].class, float[].class)
    .optModelPath(Paths.get("models/energy.onnx"))
    .build();

try (ZooModel<float[], float[]> model = ModelZoo.loadModel(criteria);
     Predictor<float[], float[]> predictor = model.newPredictor()) {
    float[] prediction = predictor.predict(inputArray);
}
```

**À préciser dans le rapport** : taille de la fenêtre temporelle d'entrée · variables utilisées · traitement des valeurs manquantes · méthode de normalisation · métriques d'évaluation · limites du modèle.

---

## 16. Étape 10 – Dockeriser

**Objectif** : permettre à un autre étudiant/l'enseignant de lancer tout le système de façon reproductible.

Chaque service Spring Boot a un **Dockerfile** :
```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**docker-compose.yml d'exemple** :
```yaml
services:
  mongo:
    image: mongo:7
    ports: ["27017:27017"]
    volumes: ["mongo_data:/data/db"]
  redis:
    image: redis:7
    ports: ["6379:6379"]
  cassandra:
    image: cassandra:5
    ports: ["9042:9042"]
    volumes: ["cassandra_data:/var/lib/cassandra"]
  collector-service:
    build: ./collector-service
    ports: ["8081:8080"]
    depends_on: [redis]
  storage-service:
    build: ./storage-service
    ports: ["8082:8080"]
    depends_on: [redis, cassandra, mongo]
  analytics-service:
    build: ./analytics-service
    ports: ["8083:8080"]
    depends_on: [cassandra, mongo]
volumes:
  mongo_data:
  cassandra_data:
```
*(Ports hôte : collector 8081, storage 8082, analytics 8083 ; tous écoutent en 8080 dans le conteneur. Volumes pour la persistance Mongo et Cassandra.)*

**Travail** :
1. Construire tous les JAR
2. Construire les images
3. Lancer `docker compose up -d`
4. Vérifier l'état des conteneurs
5. Lancer le simulateur
6. Interroger l'API
7. Arrêter/relancer la plateforme en vérifiant la persistance des données

- **Livrable** : Dockerfiles, fichier Compose, procédure de démarrage dans le README.
- **Validation** : le projet complet se lance sur une autre machine avec un nombre minimal de commandes documentées.

---

## 17. Étape 11 – Observabilité et supervision

**Objectif** : rendre visible l'état du système et mesurer son comportement.

Chaque service Spring Boot active **Actuator** ; Prometheus + Grafana en extension.

**Métriques recommandées** : mesures reçues · mesures rejetées · temps moyen de traitement · messages Redis en attente · erreurs de persistance · latence de prédiction · disponibilité de chaque service.

**Expérience** : générer une charge plus importante, observer l'évolution des temps de réponse. Pas de benchmark scientifique exigé, mais le groupe doit savoir **commenter les résultats**.

- **Livrable** : endpoints Actuator fonctionnels ; en extension, tableau de bord Grafana.
- **Validation** : montrer l'état de santé du système et **au moins 3 métriques utiles**.

---

## 18. Étape 12 – Sécurité et gestion des accès

**Objectif** : comprendre les mécanismes de protection d'une API distribuée.

**Niveau minimal** :
- Ne pas exposer de mots de passe dans Git
- Utiliser des variables d'environnement
- Valider les entrées
- Ne pas exposer inutilement les ports des bases en production
- Définir des profils de configuration

**Extensions** : Spring Security · authentification JWT · rôles ADMIN et VIEWER · OAuth2 · HTTPS via reverse proxy.

- **Livrable** : note de sécurité (secrets à protéger, accès exposés, mécanismes en place).
- **Validation** : aucun secret réel commité ; endpoints sensibles identifiés.

---

## 19. Stratégie de tests

Les tests ne sont **pas reportés à la fin** : chaque étape doit avoir une preuve de fonctionnement.

**Tests unitaires** : calculs d'énergie · détection d'anomalies · validation des objets · transformations DTO ↔ entités.

**Tests d'intégration (au minimum)** : API → Redis · Redis → Storage Service · Storage Service → Cassandra · Storage Service → MongoDB · lecture via API.

**Scénarios fonctionnels obligatoires** :
1. Envoyer une mesure valide
2. Envoyer une mesure invalide
3. Rechercher l'historique d'un capteur
4. Simuler une anomalie
5. Arrêter temporairement un service
6. Redémarrer le système
7. Vérifier la persistance des données

**Tableau de preuve de tests** (colonne « Résultat obtenu » à compléter) :

| N° | Scénario | Résultat attendu | Résultat obtenu |
|---|---|---|---|
| 1 | Envoi d'une mesure valide | HTTP 202 + événement Redis | À compléter |
| 2 | Mesure sans `sensorId` | HTTP 400 | À compléter |
| 3 | Lecture historique | Liste ordonnée de mesures | À compléter |
| 4 | Injection d'un pic | Anomalie détectée | À compléter |
| 5 | Arrêt Storage Service | Messages conservés dans Redis | À compléter |
| 6 | Redémarrage | Reprise du traitement | À compléter |

---

## 20. Organisation du travail

### Plan conseillé (adaptable)

| Période | Travaux principaux | Point de contrôle |
|---|---|---|
| J1 | Analyse, Git, architecture, contrat JSON | Architecture validée |
| J2 | Simulateur + Collector | Données reçues et validées |
| J3 | Redis Streams + consumer | Flux asynchrone fonctionnel |
| J4 | Cassandra + MongoDB | Persistance démontrée |
| J5 | API de consultation + Swagger | API testable |
| J6 | Analytics descriptif + anomalies | Indicateurs validés |
| J7 | Docker Compose + tests d'intégration | MVP exécutable |
| J8 | IA / sécurité / monitoring selon avancement | Extension documentée |
| J9 | Tests finaux, rapport, corrections | Version candidate |
| J10 | Soutenance et démonstration | Livraison finale |

*(Le document original écrit « J » au lieu de « J6 » pour la ligne Analytics ; il s'agit de la 6ᵉ journée.)*

### Répartition (groupe de 4, indicative)
- **A** : Collector, validation, Redis producer
- **B** : Storage Service, Cassandra
- **C** : MongoDB, API de consultation
- **D** : Analytics, tests, monitoring

Chaque membre doit connaître l'architecture complète (**pas de silos**) ; **revues de code croisées** recommandées.

### Points de contrôle avec l'enseignant (≥ 4, chacun avec une courte *démo*, pas que de la théorie)
1. Architecture, contrat JSON, organisation Git
2. Chaîne Collector → Redis → Storage
3. Persistance Cassandra/MongoDB et API
4. Docker, analytics, tests, préparation de la démo

---

## 21. Livrables attendus

1. Dépôt avec tout le code source
2. `docker-compose.yml`
3. Scripts d'initialisation des bases
4. Données ou simulateur nécessaires à la démo
5. Documentation OpenAPI
6. Rapport technique
7. Preuves de tests
8. Modèle IA (si extension réalisée)
9. Présentation de soutenance
10. Démonstration fonctionnelle de bout en bout

### Structure recommandée du rapport final
1. Introduction et contexte
2. Cahier des charges et besoins fonctionnels
3. Architecture générale et justification des choix
4. Modélisation des données
5. Conception et réalisation des services
6. Gestion du flux Redis
7. Persistance Cassandra et MongoDB
8. Module Analytics et IA
9. Conteneurisation et déploiement
10. Tests et résultats
11. Sécurité, limites et difficultés rencontrées
12. Améliorations possibles
13. Conclusion

Le rapport ne doit **pas être une copie du code** : il explique les décisions, compare les solutions possibles, commente les résultats.

---

## 22. Checklist avant remise (répondre « oui » à tout)

- Le projet compile sans modification manuelle ?
- Le README explique clairement le démarrage ?
- Docker Compose démarre les dépendances ?
- Le simulateur produit des données automatiquement ?
- Les requêtes invalides sont rejetées correctement ?
- Les messages sont visibles dans Redis ?
- Les mesures sont persistées dans Cassandra ?
- Capteurs et agrégats disponibles dans MongoDB ?
- L'API permet de lire l'historique ?
- Les résultats analytiques sont vérifiables ?
- Les principaux scénarios de panne ont été testés ?
- Aucun secret dans le dépôt ?
- Swagger accessible ?
- Le groupe sait expliquer ses choix sans lire le rapport ?

---

## 23. Innovations et extensions possibles (après MVP)

1. Assistant conversationnel pour interroger les indicateurs en langage naturel
2. Génération automatique de rapports
3. Notifications lors d'une anomalie
4. Kafka pour l'ingestion à plus grande échelle
5. Kubernetes pour l'orchestration
6. Interface Web temps réel
7. Système multi-bâtiments et multi-utilisateurs
8. Comparaison entre plusieurs modèles de prévision

**Règle** : toute extension doit apporter une **valeur démontrable** ; ajouter une techno juste pour augmenter le nombre d'outils n'est pas un objectif.

---

## 24. Conclusion et critères d'évaluation

SmartEnergy Hub est un projet intégrateur : backend, bases NoSQL, traitement de flux, Big Data, IA, déploiement conteneurisé. La démarche impose une progression : **d'abord un système simple et vérifiable, puis distribution, analyse et IA** — ce qui évite qu'une difficulté sur une techno bloque tout et aide à localiser l'origine d'un problème.

**La réussite est jugée sur** (et non sur le nombre de technologies) :
- la cohérence de l'architecture
- la qualité de l'implémentation
- la capacité à tester
- la justification des choix
- la maîtrise démontrée lors de la soutenance

---

## Annexe – Aide-mémoire rapide

**Codes HTTP** : 200 (health OK) · 202 (mesure acceptée) · 400 (mesure invalide).

**Noms clés** : stream `energy-measurements` · consumer group `storage-group` · keyspace `smartenergy` · table `measurements_by_sensor_day` · modèle `models/energy.onnx` · ports 8081/8082/8083 · Mongo 27017 · Redis 6379 · Cassandra 9042.

**Versions** : JDK 17 · `eclipse-temurin:17-jre` · `mongo:7` · `redis:7` · `cassandra:5`.

**Formules** : sinus + bruit (simulateur) · E ≈ ΣPᵢΔt · seuil `P_t > P_max` · z-score `(P_t−µ)/σ` · MAE · RMSE.

**Chaîne de validation de bout en bout** : simulateur → `POST /api/measurements` (202) → Redis Stream → Storage Service (ACK après persistance) → Cassandra (mesure) + MongoDB (agrégat) → API GET (historique, latest, summary, anomalies, forecast).
