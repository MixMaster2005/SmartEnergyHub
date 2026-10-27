# Décisions d'architecture (résumé)

Version complète avec alternatives écartées : `docs/cdc.md` (section 4).

| ID | Décision | Pourquoi |
|---|---|---|
| D-01 | Services séparés dont sensor-service | Respecte « chaque microservice a ses données » |
| D-02 | Deux consumer groups (storage, analytics) | Découple persistance et calcul ; écart avec l'étape 7 du guide |
| D-03 | Redis Streams (Kafka en extension `EXT-B`, CDC §18.5) | Plus léger ; contrat d'évènement portable |
| D-04 | Redis en AOF + volume | Sinon perte des messages en attente au redémarrage |
| D-05 | ACK après persistance, reprise des messages bloqués via XPENDING + XCLAIM (D-22), DLQ après 5 livraisons | At-least-once sans bloquer le flux |
| D-06 | MAXLEN ~ 500000 | Mémoire bornée ; surveiller le lag |
| D-07 | eventId = UUID v5 (sensorId|timestamp ms) | Déduplication triviale |
| D-08 | Cassandra : partition (sensor_id, day), clustering measured_at DESC, TWCS | Partitions bornées, adapté aux séries temporelles |
| D-09 | Table latest + USING TIMESTAMP = date de mesure | Lecture O(1), une mesure en retard n'écrase pas une récente |
| D-10 | Jamais de null explicite dans Cassandra | Évite les tombstones |
| D-11 | Schéma Cassandra par script CQL, schema-action none | Schéma explicite et reproductible |
| D-12 | Healthchecks + depends_on service_healthy | Cassandra démarre lentement |
| D-13 | Agrégats analytics incrémentaux (horaire + quotidien) | Pas de dépendance REST vers storage ; limite : mesures en retard |
| D-14 | Micro-batch (50 messages, 1 écriture Mongo par capteur et par lot) | Réduit les écritures sous charge |
| D-15 | Erreurs RFC 9457 | Standard, support natif Spring |
| D-16 | Chemins d'API du guide, sans /v1 | Conformité avec les endpoints évalués |
| D-17 | daily-summary servi par analytics (alias) | L'agrégat appartient à analytics |
| D-18 | Prévision : baseline puis ONNX (entraînement Python, inférence Java) | Un modèle doit battre la baseline |
| D-19 | Tests d'intégration Testcontainers | Prouve la chaîne réelle |
| D-20 | REST interne : timeout 2 s, 2 essais, circuit breaker, cache 60 s | Analytics survit à l'arrêt de sensor-service |
| D-21 | Fenêtres temporelles : mesure ≤ 24 h, futur ≤ 5 min (bornes strictes) | 24 h = reprise d'un capteur après coupure réseau (ses mesures buffered restent exploitables) ; 5 min = tolérance de dérive d'horloge, au-delà l'ordre temporel n'est plus garanti |
| D-22 | Reprise des messages inactifs via XPENDING + XCLAIM (équivalent d'XAUTOCLAIM) | Spring Data Redis 3.5.0 n'expose pas XAUTOCLAIM ; API typée et portable (Lettuce/Jedis), coût = un aller-retour Redis supplémentaire par passe |
| D-23 | Écriture Cassandra asynchrone : statements Spring (`StatementFactory`) + `CqlSession.executeAsync`, attente `allOf().join()` | Débit sans thread bloquant, mapping Spring réutilisé (nulls omis, D-10), erreur propagée = rejeu sûr du lot (writer idempotent) |
| D-24 | Métriques de stream (`energy_stream_pending` / `energy_stream_lag`) rafraîchies par une tâche planifiée (5 s), jamais dans le callback du Gauge | Sinon chaque scrape Prometheus provoquerait un aller-retour Redis ; une erreur Redis ne doit pas faire échouer un scrape (dernière valeur conservée) |
