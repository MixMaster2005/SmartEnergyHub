# sensor-service (port 8084)

**Rôle** : registre des capteurs (métadonnées et seuil `maxPowerKw`) — EF-05. **Service complet** (CRUD), à relire et tester.
**Propriétaire** de MongoDB `sensor_db` (utilisateur `sensor_app`, droits limités à cette base).

## Contrat
| Route | Réponses |
|---|---|
| `POST /api/sensors` | 201 + `Location` ; 400 ; 409 `SENSOR_ALREADY_EXISTS` |
| `GET /api/sensors?page&size&building&active` | 200 `PageResponse` (size max 100) |
| `GET /api/sensors/{id}` | 200 ; 404 `SENSOR_NOT_FOUND` |
| `PUT /api/sensors/{id}` | 200 ; 400 ; 404 ; 409 `VERSION_CONFLICT` |
| `DELETE /api/sensors/{id}` | 204 (désactivation logique) ; 404 |

`PUT` : le `sensorId` du corps doit égaler celui de l'URL ; `version` optionnelle (verrou optimiste).
`GET /api/sensors/{id}/daily-summary` **n'est pas ici** : il est servi par analytics-service (décision D-17).

## Données
`sensors` : `sensorId` (index unique `uk_sensorId`, créé par `scripts/init-mongo.js`), `name`, `building`, `floor`, `maxPowerKw`,
`active`, `tags`, `version`, `createdAt`, `updatedAt`.

## Tests
`SensorServiceTest` (Mockito, sans base) : création, doublon, `sensorId` incohérent, inconnu, taille de page.
À ajouter : test d'intégration Testcontainers MongoDB (scénario « métadonnées consultables Cassandra arrêtée »).

## Pièges
- L'unicité repose sur l'**index** MongoDB (course entre deux créations) ; le contrôle `existsBySensorId` ne suffit pas seul.
- Les index ne sont pas créés par Spring (`auto-index-creation` désactivé) : ils viennent du script d'initialisation.
