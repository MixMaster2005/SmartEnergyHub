# Rapport de campagne de tests — SmartEnergy Hub

> Copie de `resultats-modele.md` pour la campagne du 2026-10-28. Tous les résultats
> proviennent d'exécutions réelles (logs dans `/tmp/opencode/`, scripts du dépôt).

- **Date** : 2026-10-28
- **Environnement** : Linux 6.8.0-147-generic x86_64 · Docker 29.1.3 · OpenJDK 17.0.20.1 · Maven 3.8.7 · plateforme `docker compose` (Redis 7, Cassandra 5, MongoDB 7)
- **Commit** : arbre de travail au-dessus de `6345cf8` (campagne exécutée sur la plateforme reconstruite avec les correctifs ; les preuves sont commitées juste après)

| Test | Statut (OK/KO/NA) | Preuve (log, commande, capture) | Commentaire |
|---|---|---|---|
| T01 — santé | OK | `scripts/e2e.sh` : readiness ×4 + `GET /api/health` ×4 → 200 | les 4 services sains avant/après chaque scénario de panne |
| T02 — mesure valide | OK | e2e scénario 1 + `CollectorIntegrationTest.mesureValide_*` | 202, `X-Event-Id`, `XLEN` +1, entrée du stream conforme au schéma |
| T03 — champ manquant | OK | e2e scénario 2 + `CollectorIntegrationTest.sensorIdAbsent_*` | 400 `application/problem+json`, `code=VALIDATION_ERROR`, aucun évènement publié |
| T04 — valeur invalide | OK | `CollectorIntegrationTest.puissanceEnChaine_*` (`"powerKw":"3.72"`) | 400 `MALFORMED_JSON` (aucune coercition, `allow-coercion-of-scalars: false`) ; contrôle 413 > 10 Ko également OK |
| T05 — persistance Cassandra | OK | e2e scénarios 5/6 (comptage `cqlsh`) + `StorageIntegrationTest` | comptage Cassandra = mesures acceptées après reprise ; évènement du stream retrouvé via l'API |
| T06 — métadonnées MongoDB | OK | e2e scénarios 4/11 (capteurs créés via `POST /api/sensors`) + `AnalyticsIntegrationTest` | capteurs lus par analytics via sensor-service (MongoDB) |
| T07 — historique | OK | e2e scénario 3 + mesure P2 (`scripts/perf.sh`) | historique 200 ordonné/paginé, `limit=0` → 400 ; p95 47.3 ms à `limit=1000` |
| T08 — moyenne | OK | e2e scénarios 4/8 (summary analytics) + `AnalyticsIntegrationTest.mesuresValides_*` | `count`/`avgPowerKw` exacts sur l'agrégat horaire |
| T09 — dépassement | OK | e2e scénario 4 + `AnalyticsIntegrationTest.picX3_*` | anomalie `THRESHOLD`, `severity=HIGH`, `thresholdKw=10.0`, `thresholdSource=SENSOR` |
| T10 — panne du stockage | OK | e2e scénarios 5, 6, 9 | storage arrêté → lag ≥ 10 en Redis ; reprise → lag/pending = 0, aucune mesure perdue ; Redis arrêté → collector 503 + `Retry-After: 5` |
| T11 — doublon | OK | e2e scénario 8 + `StorageIntegrationTest` + `AnalyticsIntegrationTest.doublon_*` | 2×202, **une seule** ligne Cassandra, compteur analytics inchangé |
| T12 — Compose | OK | e2e scénario 7 (`down -v` → `up -d`) | readiness ×4 à 200, données préservées (volumes), historique + summary toujours servis |
| T13 — OpenAPI | OK | `curl http://localhost:808{1..4}/v3/api-docs` | 200 sur les 4 services, schémas exposés |
| T14 — bout en bout | OK | `make e2e` : **64 vérifications / 64 OK** (scénarios 1–11 + 413) | exécution unique sur plateforme vierge (`down -v && up -d`) |

## Mesures de performance (`make perf`, même date)

| Mesure | Résultat | Cible | Verdict |
|---|---|---|---|
| Historique `limit=1000` (50 requêtes) | p95 **47.3 ms**, médiane 29.7 ms | p95 < 300 ms | PASS |
| Débit collector (1 000 POST keep-alive) | **300.5 mesures/s** | ≥ 200 mesures/s | PASS |
| Bout en bout POST → Cassandra (30 essais) | p95 **163.0 ms**, médiane 116.0 ms, 30/30 | p95 < 5 s | PASS |

## Écarts et limites connues

- Quatre bugs **corrigés pendant la campagne** (avant les résultats ci-dessus) :
  1. storage `application.yml` : préfixe Boot 3.5 `spring.cassandra.*` (l'ancien `spring.data.cassandra.*` était ignoré silencieusement → crash-loop sur `127.0.0.1:9042`) ;
  2. analytics : NPE sur `lastPowerKw` null à la **première** mesure d'un capteur → lot jamais acquitté → rejeu infini puis DLQ ;
  3. analytics : deux envois rapprochés du même évènement dans le **même lot** (avant le marquage dedup) comptaient deux fois → ne garde qu'une occurrence par `eventId` ;
  4. `scripts/e2e.sh` / `scripts/perf.sh` : parser `XINFO GROUPS` (sortie non-TTY), `XADD '*'` glob, comparaison content-type avec `;charset=UTF-8`, `date -F` invalide, corps sans saut de ligne (xargs n'envoyait qu'**une** requête pour 1 100), P3 sur `/latest` (cache TTL 30 s) et timestamps identiques (déduction du dédup).
- Méthodologie P1 : le débit mesuré dépend fortement de la réutilisation de connexion. Avec un
  processus curl par requête : ~68 mesures/s (artefact de banc). Keep-alive séquentiel : 300.5/s.
- `/latest` est un cache-aside Redis (TTL 30 s, jamais invalidé à l'écriture) : une mesure fraîche
  peut mettre jusqu'à 30 s à apparaître sur `/latest` (comportement attendu, D-09). Les mesures
  P3 pollent l'historique (lecture Cassandra directe) pour cette raison.
- `CassandraMappingTest` peut échouer au **lancement** du conteneur si Docker est chargé
  (conteneurs e2e/perf/tests simultanés) : passé isolé en 40 s (5/5). Non déterministe, lié à
  l'environnement, pas au code.
- L'**étape 9C (prévision IA)** est hors périmètre de cette campagne : `forecast` est désactivé
  par défaut et renvoie 501 `FORECAST_DISABLED`.
- Les **3 tests `sensor-service`** restants de la partie A du `TODO-MAP` ont été écrits **après**
  cette campagne (même date) : `mvn -B test` complet → **94/94 verts** (76 unitaires + 18
  d'intégration, dont `SensorServiceIntegrationTest`), voir `docs/tests.md`.
