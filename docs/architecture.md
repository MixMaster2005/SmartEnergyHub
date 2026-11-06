# Architecture

## Flux de données

1. Le **simulateur** (ou un capteur) envoie `POST /api/measurements` au **collector-service**.
2. Le collector valide, calcule un `eventId` déterministe, publie dans le stream Redis `energy-measurements` (`XADD`)
   et répond **202**. Redis KO → **503** + `Retry-After` (jamais de 202 sans publication).
3. Deux **consumer groups** lisent le même stream, indépendamment :
   - `storage-group` → **storage-service** → Cassandra (`measurements_by_sensor_day`, `latest_measurement_by_sensor`) ;
   - `analytics-group` → **analytics-service** → MongoDB `analytics_db` (agrégats horaires/quotidiens, anomalies).
4. L'utilisateur consulte : historique (storage), capteurs (sensor), indicateurs et anomalies (analytics).
5. analytics appelle sensor-service (`GET /api/sensors/{id}`, cache Redis 60 s, circuit breaker) pour connaître le seuil d'un capteur ;
   repli : seuil par défaut.

## Interface Web (EXT-UI)

L'interface est une application **statique** servie par la gateway sur `:8080` ; elle ne
possède aucune donnée et n'ouvre aucune connexion directe aux bases.

- **Fichiers** : `frontend/` (source unique) est monté dans le conteneur gateway
  (`./frontend:/app/frontend:ro`) et copié dans `classpath:/static/` au build du jar
  (D-29), ce qui rend la passerelle autonome.
- **Routage** : par hash (`#/sensors/SENSOR-001`, D-30). Le serveur ne voit que `/` :
  aucun fallback SPA ni 404 au rafraîchissement.
- **Lecture** : toutes les URL sont relatives à l'origine de la page, donc sans CORS.
  Chaque vue interroge uniquement les routes publiques §5.4 et les routes ops
  `/collector|storage|analytics|sensor/**` (D-26) pour la santé et les métriques.
- **Écriture** : une seule exception, la page Scénarios, qui publie des mesures de
  recette sur le capteur réservé `UI-TEST-001` (jamais les capteurs du simulateur).
- **Sondage** : 5 s pour les vues temps réel, 10 s pour la santé d'en-tête ; pause quand
  l'onglet est caché, backoff borné en cas d'erreur, un seul appel à la fois.
- **Graphiques** : Chart.js 4.4.7 vendorisé (MIT), aucun CDN.
- **Preuves** : `tools/capture/capture.mjs` (Playwright) vérifie les 6 vues et produit
  `preuves/captures/ext-ui/` (PNG + `capture-report.json`).

## Propriété des données

| Service | Données |
|---|---|
| storage-service | Cassandra `smartenergy` |
| sensor-service | MongoDB `sensor_db.sensors` |
| analytics-service | MongoDB `analytics_db` : `sensor_state`, `hourly_aggregates`, `daily_aggregates`, `anomalies`, `forecasts` |
| collector-service | aucune (le stream Redis est en transit) |

Aucun service ne lit la base d'un autre.

## Fiabilité

- **At-least-once** : ACK uniquement après persistance ; messages en attente réclamés (`XPENDING` + `XCLAIM`, décision D-22) ; DLQ après 5 livraisons
  ou si l'évènement est illisible.
- **Idempotence** : `eventId` = UUID v5 de `sensorId|timestamp` ; clé primaire Cassandra naturelle ; dédup Redis côté analytics.
- **Redis persistant** : AOF + volume (sinon un redémarrage de Redis perd les messages en attente).
- **Disponibilité partielle** : collector indépendant de storage/analytics ; analytics indépendant de sensor-service.

## Conventions

JSON camelCase · dates ISO-8601 UTC avec `Z` · intervalles `[from, to)` · erreurs RFC 9457 (`application/problem+json`) ·
identifiant capteur `^[A-Z0-9-]{3,32}$` · chemins d'API identiques au guide (sans préfixe de version).
