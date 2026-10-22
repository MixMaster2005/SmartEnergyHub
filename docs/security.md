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
- Conteneurs applicatifs : utilisateur non root.
- Limitation de taille du corps HTTP (10 Ko → 413) : **prévue au CDC (étape 12), non
  implémentée** (`BodySizeLimitFilter` = squelette dans `collector-service`) — à ne pas
  confondre avec les tableaux de contrats qui affichent déjà le 413.

## Extensions possibles
Spring Security + JWT (rôles ADMIN / VIEWER), authentification Cassandra, TLS (reverse proxy), limitation de débit, OAuth2.
