# Note de sécurité (livrable de l'étape 12)

À compléter avec les choix réellement mis en œuvre.

## Secrets à protéger
| Secret | Où il vit | Jamais dans |
|---|---|---|
| `REDIS_PASSWORD` | `.env` | dépôt Git, logs |
| `MONGO_ROOT_PASSWORD`, `MONGO_SENSOR_PASSWORD`, `MONGO_ANALYTICS_PASSWORD` | `.env` | dépôt Git |

- `.env.example` (**versionné**) ne contient que des **placeholders** (`change-me...`) ;
  `cp .env.example .env` avant le premier démarrage (`make up` le fait automatiquement).
- Le `.env` de développement est actuellement versionné mais ne contient **que des
  identifiants de dev placeholders** — jamais de secret réel. Avant toute mise en
  production, le désindexer (`git rm --cached .env`) et le compléter hors Git.

## Accès exposés
| Élément | Exposition | Décision |
|---|---|---|
| Ports Redis / MongoDB / Cassandra | publiés **uniquement** par `docker-compose.override.yml` (développement) | non exposés en production |
| Actuator | `health`, `info`, `metrics`, `prometheus` | accessible aussi derrière la gateway (`/collector/**`, `/storage/**`, `/analytics/**`, `/sensor/**`, D-26) ; en production, restreindre les ports directs (retirer `ports:` ou filtrer au pare-feu) et/ou exiger l'authentification `EXT-12`. **`show-details` reste désactivé** (D-32) : l'interface n'affiche que `UP`/`DOWN`, aucun composant interne n'est exposé. |
| Interface EXT-UI | fichiers statiques sur `:8080/` | lecture seule via les routes publiques ; une écriture confinée au capteur de recette `UI-TEST-001` (page Scénarios) ; aucune donnée sensible, aucun secret côté navigateur |
| `POST /api/measurements` | public | à protéger par clé d'API ou JWT (extension `EXT-12`, CDC §18.3) |
| `POST/PUT/DELETE /api/sensors` | public | réservé au rôle ADMIN (extension `EXT-12`, CDC §18.3) |

## Mécanismes en place
- Mots de passe par variables d'environnement ; profils `docker` / défaut.
- MongoDB : un utilisateur par base, droits limités (`scripts/init-mongo.js`).
- Redis : `requirepass`.
- Validation stricte des entrées (contrats partagés), pas de coercition de types, messages d'erreur sans détail interne.
- Erreurs REST uniformes en `application/problem+json` avec `code` et `traceId`
  (`GlobalExceptionHandler`, enregistré en `@ControllerAdvice` — sans cette annotation,
  Spring ignorait le handler et chaque service retombait sur l'erreur JSON par défaut).
  Les exceptions standard de Spring MVC (404 route inconnue, 405, 415) passent par la classe
  de base avec un corps `null` : l'enrichissement se fait donc **après**
  `super.handleExceptionInternal` (sinon `type` restait `about:blank` et `code`/`traceId`
  absents) — preuve : `GlobalExceptionHandlerTest`.
- Conteneurs applicatifs : utilisateur non root.
- **Actuator derrière la gateway (EXT-GW, livré)** : la gateway expose des routes
  d'exploitation uniformes `/collector/**`, `/storage/**`, `/analytics/**`, `/sensor/**`
  (préfixe retiré) vers readiness, `/api/health` et Actuator. C'est le **mécanisme**
  prévu par la note ; il ne restreint pas à lui seul les accès directs. En production,
  supprimer la publication des ports 8081–8084 (ou la filtrer) et activer `EXT-12`
  pour l'authentification. En développement, les ports restent publiés (D-25).
- **Limitation de taille du corps HTTP (étape 12, livrée)** : `POST /api/measurements` borné à
  **10 Ko** par `BodySizeLimitFilter` → 413 `PAYLOAD_TOO_LARGE` en `application/problem+json`.
  Deux cas couverts : `Content-Length` annoncé (le filtre écrit la réponse lui-même, le filtre
  s'exécutant avant le `DispatcherServlet`) et corps **chunked** sans `Content-Length` (flux
  compteur qui lève `ApiException`, résolue par le handler global). Limite configurable :
  `MEASUREMENT_MAX_BODY_BYTES` (défaut `10240`). Preuves : `BodySizeLimitFilterTest` (5 cas) et le
  contrôle « corps > 10 Ko » de `make e2e` (413 + `XLEN` inchangé) — c'est ce filtre qui produit
  aussi le 413 annoncé par les tableaux de contrats.
- **Interface EXT-UI (D-28 à D-33)** : fichiers statiques sans étape de build, servis
  par la gateway. Aucun secret n'est présent côté navigateur, aucune requête ne sort
  de l'origine (Chart.js est vendorisé, pas de CDN). Toute valeur reçue de l'API est
  échappée (`escapeHtml`) avant insertion HTML ; les erreurs affichées se limitent à
  `code`, `message` et `traceId`. L'interface est en **lecture seule**, sauf la page
  Scénarios qui écrit uniquement dans `UI-TEST-001`. Son authentification relève de
  `EXT-12`, comme les autres routes publiques.

## Extensions possibles — `EXT-12` (détail CDC §18.3)

Spring Security + JWT (rôles `ADMIN` / `VIEWER`), clé d'API pour `POST /api/measurements`,
authentification applicative, TLS (reverse proxy), limitation de débit (souvent couplée à la
gateway `EXT-GW`), OAuth2.

**État** : aucun fichier, rien n'est codé. **Critère** : écriture des capteurs et collecte refusées
sans jeton / clé (401 ou 403 en `application/problem+json`), `make e2e` adapté reste vert.
**Renvois** : `docs/cdc.md` §18.3 · `docs/TODO-MAP.md` partie B · README racine §10.2 ·
README `collector-service` et `sensor-service`.
