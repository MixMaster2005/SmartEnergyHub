# Note de sécurité (livrable de l'étape 12)

À compléter avec les choix réellement mis en œuvre.

## Secrets à protéger
| Secret | Où il vit | Jamais dans |
|---|---|---|
| `REDIS_PASSWORD` | `.env` (ignoré par Git) | dépôt Git, logs |
| `MONGO_ROOT_PASSWORD`, `MONGO_SENSOR_PASSWORD`, `MONGO_ANALYTICS_PASSWORD` | `.env` | dépôt Git |

`.env.example` ne contient que des **placeholders** (`change-me...`).

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

## Extensions possibles
Spring Security + JWT (rôles ADMIN / VIEWER), authentification Cassandra, TLS (reverse proxy), limitation de débit, OAuth2.
