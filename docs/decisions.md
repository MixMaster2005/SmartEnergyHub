# Décisions d'architecture (résumé)

Version complète avec alternatives écartées : cahier des charges dans Notion (section 4).

| ID | Décision | Pourquoi |
|---|---|---|
| D-01 | Services séparés dont sensor-service | Respecte « chaque microservice a ses données » |
| D-02 | Deux consumer groups (storage, analytics) | Découple persistance et calcul ; écart avec l'étape 7 du guide |
| D-03 | Redis Streams (Kafka en extension) | Plus léger ; contrat d'évènement portable |
| D-04 | Redis en AOF + volume | Sinon perte des messages en attente au redémarrage |
| D-05 | ACK après persistance, XAUTOCLAIM, DLQ après 5 livraisons | At-least-once sans bloquer le flux |
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
