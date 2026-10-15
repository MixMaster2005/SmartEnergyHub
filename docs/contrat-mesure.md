# Contrat de données — SmartEnergy Hub

Ce document est le **contrat partagé** entre le simulateur, le Collector, le Storage
Service et l'Analytics/API. Il doit être stabilisé avant tout développement parallèle
(cf. CDC §14 et §13.4).

## 1. Message public d'une mesure (HTTP)

Endpoint : `POST /api/measurements` (Collector Service).

```json
{
  "sensorId": "SENSOR-001",
  "timestamp": "2026-09-30T08:15:00Z",
  "powerKw": 3.72,
  "voltage": 226.4,
  "temperature": 29.1
}
```

| Champ | Type | Obligatoire | Règle de validation |
|---|---|---|---|
| `sensorId` | String | oui | non vide ; correspond à un capteur connu pour le traitement métier |
| `timestamp` | Instant ISO-8601 | oui | format valide ; **UTC recommandé** |
| `powerKw` | Nombre décimal | oui | `>= 0` |
| `voltage` | Nombre décimal | oui | `> 0` ; plage plausible configurable |
| `temperature` | Nombre décimal | oui | plage réaliste définie dans le simulateur |

### Réponses HTTP

| Code | Signification |
|---|---|
| `202 Accepted` | Mesure acceptée pour traitement (pas encore persistée — RG-04). |
| `400 Bad Request` | Données invalides ; **aucun** événement Redis publié (EF-05, RG-03). |
| `503 Service Unavailable` | Publication Redis impossible. |

## 2. Événement interne (Redis Streams)

Stream : `energy-measurements`. Chaque entrée reprend les champs publics et ajoute un
`eventId` UUID propre à l'événement interne (proposition CDC §6.2) :

```
XADD energy-measurements * sensorId SENSOR-001 eventId <uuid> \
    timestamp 2026-09-30T08:15:00Z powerKw 3.72 voltage 226.4 temperature 29.1
```

- Consumer group : `storage-group`.
- Lecture : `XREADGROUP GROUP storage-group <consumer> COUNT n BLOCK ms STREAMS energy-measurements >`.
- Acquittement : `XACK energy-measurements storage-group <id>` **après** persistance (RG-05).
- Idempotence : le Storage Service ignore un `eventId` déjà persisté (T11, RG-06).

## 3. Document capteur (MongoDB)

Collection : `sensors`.

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

- `sensorId` : identifiant unique (index unique).
- `maxPowerKw` : seuil métier ; dépassement si `powerKw > maxPowerKw` (RG-07).

## 4. Document agrégat (MongoDB)

Collection : `aggregates`. Au minimum :

```json
{
  "sensorId": "SENSOR-001",
  "periodStart": "2026-09-30T08:00:00Z",
  "periodEnd": "2026-09-30T09:00:00Z",
  "avgPowerKw": 4.21,
  "minPowerKw": 1.10,
  "maxPowerKw": 7.85,
  "sampleCount": 120,
  "anomalyCount": 3,
  "energyKwh": 0.84
}
```

Agrégats calculables : **moyenne, min, max, nombre de mesures, nombre de dépassements,
énergie estimée**.

## 5. Modèle Cassandra

Table : `measurements_by_sensor_day` (script `scripts/init-cassandra.cql`).

```cql
CREATE TABLE measurements_by_sensor_day (
    sensor_id    text,
    day          date,
    measured_at  timestamp,
    event_id     text,
    power_kw     double,
    voltage      double,
    temperature  double,
    PRIMARY KEY ((sensor_id, day), measured_at, event_id)
) WITH CLUSTERING ORDER BY (measured_at DESC, event_id ASC);
```

- Requête typique : historique d'un capteur sur une plage, tri décroissant.
- `event_id` en colonne de clustering lève l'ambiguïté des horodatages identiques et
  sert de garde-fou d'idempotence.

## 6. Règles de gestion (rappel)

- **RG-01** : toute mesure est associée à un `sensorId` identifiable.
- **RG-02** : horodatage conservé ; périodes sans ambiguïté (ISO-8601, UTC).
- **RG-03** : requête invalide → `400`, rien dans Redis.
- **RG-04** : `202` = acceptée pour traitement, pas encore persistée.
- **RG-05** : `XACK` seulement après persistance.
- **RG-06** : rejeu de doublons sans double comptage.
- **RG-07** : dépassement si `powerKw > maxPowerKw`.
- **RG-08** : brutes dans Cassandra ; descriptif/agrégats dans MongoDB.
- **RG-09** : statistiques sur intervalle explicite `from`/`to` + capteur explicite.
- **RG-10** : énergie en kWh estimée à partir des horodatages (intervalles réels).
