# Preuves de tests (matrice de recette)

À compléter au fil de l'avancement. Les scénarios 1 à 7 reprennent le tableau du guide ; 8 à 11 sont ajoutés par le cahier des charges.
Automatisation partielle : `make e2e` (`scripts/e2e.sh`, scénarios 1 à 3 ; le reste est commenté TODO).

> **Numérotations** : cette matrice utilise les scénarios **1–11** (CDC) ; le gabarit de
> preuves `preuves/tests/resultats-modele.md` utilise les labels **T01–T14** du guide.
> Les deux référent le même recette.

## Tests unitaires présents dans le dépôt (64 tests actifs)

| Classe | Service | Cas |
|---|---|---|
| `MeasurementTimeValidatorTest` | collector | 8 (bornes 24 h / 5 min, horloge fixe) |
| `MeasurementPublisherTest` | collector | 3 (XADD, MAXLEN, 503 Redis KO) |
| `MeasurementControllerTest` | collector | 4 (202 + X-Event-Id, 400, 503) |
| `SensorServiceTest` | sensor | 5 (création, doublon, sensorId incohérent, inconnu, page) |
| `EventIdsTest` | contracts | 4 (UUID v5 déterministe) |
| `MeasurementEventCodecTest` | contracts | 7 (aller-retour du codec d'événement) |
| `MeasurementKeyTest` | storage | 3 (clés primaires composites, égalité) |
| `CassandraMappingTest` | storage | 5 (mapping entités sur Cassandra Testcontainers, D-08 à D-10) |
| `CassandraMeasurementWriterTest` | storage | 4 (rejeu → une seule ligne, `USING TIMESTAMP` sur `latest`, nulls omis, erreur propagée + métriques, D-09 à D-10, D-23) |
| `HistoryCursorTest` | storage | 8 (aller-retour du curseur base64url, jeton non URL-safe, collisions, jetons corrompus → 400 `INVALID_PARAMETER`, étape 8) |
| `AnalyticsRulesTest` | analytics | 9 (énergie rectangle tronquée, seuil strict, Welford, score z, limites documentées) |
| `StreamLagMetricsTest` | platform | 4 (noms de métriques + tag `group`, refresh pending/lag, tolérance d'une panne Redis, lag inconnu/groupe absent = 0, D-24) |

Squelettes **entièrement commentés** (aucun test exécuté à ce jour) :
`CollectorIntegrationTest`, `StorageIntegrationTest`, `AnalyticsIntegrationTest`.
`mvn -B test` exécute la suite actuelle (contrats + tous les services).

| N° | Scénario | Résultat attendu | Preuve | Résultat obtenu |
|---|---|---|---|---|
| 1 | Mesure valide | HTTP 202 et entrée dans le stream | réponse + `XLEN` | À compléter |
| 2 | Mesure sans `sensorId` | HTTP 400 Problem Details, aucune entrée | réponse + `XLEN` inchangé | À compléter |
| 3 | Historique d'un capteur | Liste ordonnée, paginée, bornée par `from`/`to` | appel API | À compléter |
| 4 | Injection d'un pic ×3 | Anomalie `THRESHOLD` listée | `/anomalies` | À compléter |
| 5 | Arrêt de storage-service pendant l'envoi | Messages conservés dans Redis (pending/lag en hausse) | `XINFO GROUPS` + `/actuator/metrics/energy_stream_pending` | À compléter |
| 6 | Redémarrage de storage-service | Reprise, aucune mesure perdue (comptage Cassandra = mesures acceptées) | comptage | À compléter |
| 7 | Redémarrage complet de la plateforme | Données toujours présentes (volumes) | appels API | À compléter |
| 8 | Doublon | Une seule ligne Cassandra, compteur analytics inchangé | API + agrégat | À compléter |
| 9 | Redis arrêté | Collector répond 503 + `Retry-After` | réponse | À compléter |
| 10 | Message poison dans le stream | Envoyé en DLQ, le flux continue | `XLEN` de la DLQ | À compléter |
| 11 | sensor-service arrêté | Analytics traite avec le seuil par défaut | anomalie `thresholdSource = DEFAULT` | À compléter |

## Mesures de performance (étapes 5 et 11)

| Mesure | Méthode | Résultat | Commentaire |
|---|---|---|---|
| Lecture d'un jour d'historique (`limit` 1000) | À compléter | | cible p95 < 300 ms |
| Débit soutenu du collector | À compléter | | cible ≥ 200 mesures/s |
| Retard de bout en bout (réception → Cassandra) | À compléter | | cible p95 < 5 s |

## Checklist avant remise

Reprendre la checklist du guide (section 28) : compilation sans modification manuelle, README clair, Docker Compose, simulateur,
requêtes invalides rejetées, messages visibles dans Redis, mesures dans Cassandra, capteurs/agrégats dans MongoDB, historique via API,
résultats analytiques vérifiables, scénarios de panne testés, secrets absents du dépôt, Swagger accessible.
