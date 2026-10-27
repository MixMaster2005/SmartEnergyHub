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
| Actuator | `health`, `info`, `metrics`, `prometheus` | à restreindre derrière la gateway en production |
| `POST /api/measurements` | public | à protéger par clé d'API ou JWT (extension) |
| `POST/PUT/DELETE /api/sensors` | public | réservé au rôle ADMIN (extension) |

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
- **Limitation de taille du corps HTTP (étape 12, livrée)** : `POST /api/measurements` borné à
  **10 Ko** par `BodySizeLimitFilter` → 413 `PAYLOAD_TOO_LARGE` en `application/problem+json`.
  Deux cas couverts : `Content-Length` annoncé (le filtre écrit la réponse lui-même, le filtre
  s'exécutant avant le `DispatcherServlet`) et corps **chunked** sans `Content-Length` (flux
  compteur qui lève `ApiException`, résolue par le handler global). Limite configurable :
  `MEASUREMENT_MAX_BODY_BYTES` (défaut `10240`). Preuves : `BodySizeLimitFilterTest` (5 cas) et le
  contrôle « corps > 10 Ko » de `make e2e` (413 + `XLEN` inchangé) — c'est ce filtre qui produit
  aussi le 413 annoncé par les tableaux de contrats.

## Extensions possibles
Spring Security + JWT (rôles ADMIN / VIEWER), authentification Cassandra, TLS (reverse proxy), limitation de débit, OAuth2.
