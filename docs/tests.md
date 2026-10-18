# Preuves de tests (matrice de recette)

À compléter au fil de l'avancement. Les scénarios 1 à 7 reprennent le tableau du guide ; 8 à 11 sont ajoutés par le cahier des charges.
Automatisation partielle : `make e2e` (`scripts/e2e.sh`).

| N° | Scénario | Résultat attendu | Preuve | Résultat obtenu |
|---|---|---|---|---|
| 1 | Mesure valide | HTTP 202 et entrée dans le stream | réponse + `XLEN` | À compléter |
| 2 | Mesure sans `sensorId` | HTTP 400 Problem Details, aucune entrée | réponse + `XLEN` inchangé | À compléter |
| 3 | Historique d'un capteur | Liste ordonnée, paginée, bornée par `from`/`to` | appel API | À compléter |
| 4 | Injection d'un pic ×3 | Anomalie `THRESHOLD` listée | `/anomalies` | À compléter |
| 5 | Arrêt de storage-service pendant l'envoi | Messages conservés dans Redis (pending/lag en hausse) | `XINFO GROUPS` | À compléter |
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
