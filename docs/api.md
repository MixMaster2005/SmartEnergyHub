# API REST — SmartEnergy Hub

Base URL locale : `http://localhost:8083` (Analytics/API Service).
Documentation interactive : `http://localhost:8083/swagger-ui.html` (OpenAPI `EF-14`).

Ports par défaut : Collector `8081`, Storage `8082`, Analytics/API `8083`.

## 1. Endpoints

| Méthode | Route | Service | But | Réponses |
|---|---|---|---|---|
| `POST` | `/api/measurements` | Collector | Recevoir une mesure. | `202` acceptée ; `400` invalide ; `503` publication impossible |
| `POST` | `/api/sensors` | Analytics/API | Créer les métadonnées d'un capteur. | `201` créé ; `400` invalide ; `409` identifiant déjà utilisé |
| `GET` | `/api/sensors` | Analytics/API | Lister les capteurs. | `200` liste (éventuellement vide) |
| `GET` | `/api/sensors/{id}` | Analytics/API | Consulter un capteur. | `200` trouvé ; `404` inconnu |
| `PUT` | `/api/sensors/{id}` | Analytics/API | Modifier métadonnées / seuil. | `200` modifié ; `400` invalide ; `404` inconnu |
| `GET` | `/api/measurements/{sensorId}?from=...&to=...` | Analytics/API | Historique sur une période. | `200` liste ordonnée ; `400` période invalide |
| `GET` | `/api/measurements/{sensorId}/latest` | Analytics/API | Dernière mesure. | `200` mesure ; `404` aucune mesure |
| `GET` | `/api/analytics/{sensorId}/summary?from=...&to=...` | Analytics/API | Moyenne, min, max, nombre, énergie estimée. | `200` agrégat ; `400` période invalide |
| `GET` | `/api/analytics/{sensorId}/anomalies?from=...&to=...` | Analytics/API | Mesures dépassant le seuil. | `200` liste |

## 2. Convention de réponse

- Codes HTTP cohérents + messages d'erreur compréhensibles.
- Dates au format **ISO-8601** en **UTC** ; validation `from <= to`.
- Jamais de succès (`2xx`) si la requête n'a pas été traitée selon le contrat.
- Swagger fournit des exemples valides **et** invalides.

Exemple d'erreur de validation :

```json
{
  "timestamp": "2026-09-30T08:15:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "powerKw doit être supérieur ou égal à 0",
  "path": "/api/measurements"
}
```

## 3. Exemples

Mesure valide :

```bash
curl -i -X POST http://localhost:8081/api/measurements \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-001","timestamp":"2026-09-30T08:15:00Z","powerKw":3.72,"voltage":226.4,"temperature":29.1}'
```

Mesure invalide (attendu `400`) :

```bash
curl -i -X POST http://localhost:8081/api/measurements \
  -H 'Content-Type: application/json' \
  -d '{"timestamp":"2026-09-30T08:15:00Z","powerKw":-1}'
```

Créer un capteur :

```bash
curl -i -X POST http://localhost:8083/api/sensors \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"SENSOR-001","name":"Compteur salle serveur","building":"Bâtiment A","floor":1,"maxPowerKw":8.0,"active":true,"tags":["critical"]}'
```

Résumé sur une période :

```bash
curl "http://localhost:8083/api/analytics/SENSOR-001/summary?from=2026-09-30T00:00:00Z&to=2026-09-30T23:59:59Z"
```

Anomalies (dépassements de `maxPowerKw`) :

```bash
curl "http://localhost:8083/api/analytics/SENSOR-001/anomalies?from=2026-09-30T00:00:00Z&to=2026-09-30T23:59:59Z"
```

## 4. Santé / observabilité

Chaque service expose `GET /actuator/health` (Actuator) :

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

> Note : ces routes sont le **contrat cible**. Le squelette actuel fournit les services
> Spring Boot avec Actuator ; les contrôleurs métier sont ajoutés à l'étape suivante.
