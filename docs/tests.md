# Stratégie de tests — SmartEnergy Hub

Les résultats doivent être **réels** et conservés dans `preuves/`.

## 1. Tests du CDC (§10)

| ID | Procédure | Résultat attendu / preuve |
|---|---|---|
| T01 — santé | Démarrer chaque service, appeler `/actuator/health`. | HTTP 200, services accessibles. |
| T02 — mesure valide | `POST` une mesure correcte. | `202` + événement visible dans Redis. |
| T03 — champ manquant | Mesure sans `sensorId`. | `400` + aucun nouvel événement Redis. |
| T04 — valeur invalide | `powerKw` négatif ou `timestamp` incorrect. | `400` + message explicite. |
| T05 — persistance | Envoyer une mesure, attendre le Storage. | Mesure retrouvée dans Cassandra. |
| T06 — métadonnées | Créer un capteur via l'API et le relire. | Document cohérent, seuil disponible. |
| T07 — historique | Mesures à horodatages distincts, requête `from/to`. | Liste triée, limitée à la période. |
| T08 — moyenne | Injecter 2, 4, 6 kW. | Moyenne = 4 kW. |
| T09 — dépassement | `maxPowerKw=5`, injecter 7 kW. | La mesure apparaît dans les anomalies. |
| T10 — panne stockage | Arrêter le Storage, envoyer, redémarrer. | Messages traités après reprise. |
| T11 — doublon | Rejouer un même `eventId`. | Pas de double comptage. |
| T12 — Compose | Démarrer la stack via la commande documentée. | Dépendances disponibles, services connectés. |
| T13 — OpenAPI | Ouvrir Swagger UI, exécuter les routes. | Routes/paramètres/réponses compréhensibles. |
| T14 — bout en bout | 5 capteurs ≥ 2 min + pic (commande en §3, « Test 2 minutes »), consulter historique/anomalies. | Chaîne démontrée de bout en bout. |

## 2. Niveaux de tests

- **Unitaires** : validation DTO, calculs (moyenne/min/max/énergie), idempotence.
- **Intégration** : `@SpringBootTest` avec conteneurs (Redis/Cassandra/Mongo) ou mocks ;
  repository + consumer.
- **Bout en bout** : simulateur → Collector → Redis → Storage → bases → API.

## 3. Commandes

```bash
# Tests par service
cd collector-service && ./mvnw test
cd storage-service   && ./mvnw test
cd analytics-service && ./mvnw test

# Vérifications manuelles infra
docker-compose up -d
docker exec smartenergy-redis redis-cli XLEN energy-measurements
docker exec smartenergy-redis redis-cli XINFO GROUPS energy-measurements
docker exec smartenergy-cassandra cqlsh -e "SELECT * FROM smartenergy.measurements_by_sensor_day LIMIT 5;"
docker exec smartenergy-mongodb mongosh -u root -p root_dev --authenticationDatabase admin \
  --eval "db.getSiblingDB('smartenergy').sensors.find().pretty()"
```

### Test 2 minutes (simulation seule, CDC §10.2 — T14)

Simulation locale autonome (sans Collector ni HTTP) : 5 capteurs en continu pendant
120 s, une mesure toutes les 2 s, avec un pic ×3 au milieu du test.

```bash
cd simulator
python3 preview_measurements.py --sensors 5 --duration-seconds 120 \
  --interval-seconds 2 --seed 42 --peak-at-second 60 --peak-duration 10
```

Attendu : **300 mesures** (5 capteurs × 60 cycles) imprimées en continu, pic ×3 sur
`SENSOR-001` entre t=60 s et t=70 s, fin `CONTRAT VALIDE`. Preuve :
`preuves/tests/` (sortie console ci-dessus).

## 4. Jeux de données de référence

- Moyenne : `[2, 4, 6] kW` → moyenne `4`.
- Dépassement : `maxPowerKw = 5`, mesure `7` → anomalie.
- Idempotence : même `eventId` envoyé deux fois → agrégats inchangés.

## 5. Preuves

Consigner captures et sorties dans `preuves/tests/` et `preuves/captures/`
(voir le gabarit `preuves/tests/resultats-modele.md`).
