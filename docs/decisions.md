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
| D-25 | API Gateway **additive** : profil Compose `gateway` sur `:8080` (5 routes métier dans l'ordre D-17, Swagger agrégé, CORS) ; les ports 8081–8084 **restent publiés** | La gateway est une porte d'entrée de plus, pas un remplacement : accès direct conservé pour l'IDE, les Swagger par service, le diagnostic et la compatibilité des scripts existants. Le profil permet de l'activer sans imposer un saut réseau en développement. |
| D-26 | Routes **ops** uniformes en plus des routes métier : `/collector/**`, `/storage/**`, `/analytics/**`, `/sensor/**` → service correspondant avec `StripPrefix=1` ; 4 routes `/v3/api-docs/{service}` pour l'agrégation | Le CDC §7.6 ne couvre que `/api/**`. `make e2e` doit joindre readiness et `/api/health` par la gateway ; ces préfixes explicites gardent les contrôles d'exploitation disponibles sur l'origine unique, sans exposer une route fourre-tout. Écart assumé au tableau §7.6 (documenté dans `docs/cdc.md`). |
| D-27 | Délais de recette élargis : `READY_TIMEOUT=600 s` et `WAIT_TIMEOUT=90 s` (surchargeables par variables d'environnement) | Le saut réseau supplémentaire (gateway) et le démarrage Cassandra peuvent allonger les attentes. Des délais plus larges évitent des échecs de timing sans masquer une panne : chaque attente est bornée et le script échoue toujours en cas de non-réponse. |
| D-28 | Interface Web en **modules ES natifs** + Chart.js 4.4.7 **vendorisé**, sans étape de build ni CDN | Déploiement statique trivial (aucune toolchain Node en production), fonctionne hors ligne, compatible avec une CSP stricte. Chart.js est la seule dépendance, servie depuis le jar. |
| D-29 | **Source unique** `frontend/` ; le build de la gateway copie `../frontend` dans `classpath:/static/`, et Compose monte aussi `./frontend:/app/frontend:ro` | Le jar reste autonome (démo sans volume) tout en gardant l'édition à chaud en développement. Une seule copie des sources : pas de divergence entre les deux modes. |
| D-30 | Routage **par hash** (`#/dashboard`, `#/sensors/{id}`), sans fallback SPA côté serveur | Un rafraîchissement ne produit jamais de 404 : le serveur ne voit que `/`. La gateway n'a pas de règle de réécriture à maintenir et les liens sont partageables. |
| D-31 | Compteur `energy_measurements_received_total{result=accepted|rejected}` dans le collector, via un filtre enveloppant placé avant la limite de corps | Le CDC §9 promet ce compteur ; un filtre voit les 400/413/503 que le contrôleur ne traverse pas. Deux valeurs seulement pour rester conforme au contrat. |
| D-32 | Actuator **sans** `management.endpoint.health.show-details` : l'interface n'affiche que `UP`/`DOWN` | Aucun détail interne (composants, chemins, versions) n'est exposé sur une route publique. L'interface n'en a pas besoin : elle joint chaque service via les routes ops (D-26). |
| D-33 | Nouvelle extension **`EXT-UI`** (CDC §18.6) plutôt qu'un ajout à `EXT-B` | L'interface a ses propres exigences, fichiers, activation et critères de recette (6 vues, 84 tests, captures). La rattacher à `EXT-B` aurait mélangé une livraison concrète avec des pistes d'évolution non planifiées. |
