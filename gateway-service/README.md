# gateway-service — `EXT-GW` (extension, non codé)

Point d'entrée unique en `:8080` (Spring Cloud Gateway) : routage, JWT, CORS, Swagger agrégé.

## Extension (hors MVP)

| Gabarit CDC §18.4 | |
|---|---|
| **État** | scaffolding non codé (fichiers `.example` uniquement, module absent du pom parent et du Compose) |
| **Exigences** | CDC §7.6, D-16 (versioning d'URL), D-17 (routage du `daily-summary`) |
| **Activation** | renommer les `.example`, ajouter le module au pom parent, profil Compose `gateway`, retrait des ports 8081–8084 |
| **Critère** | les 5 routes répondent via `:8080` avec l'ordre respecté, Swagger agrégé, CORS + rate-limit |
| **Renvois** | CDC §18.4 · `docs/TODO-MAP.md` partie B · README racine §10.2 · `docs/security.md` |

Fichiers fournis : `routes.yml.example` (table de routage) et `pom.xml.example` (squelette de dépendances).

## Routage (l'ordre compte)
| Prédicat | Cible |
|---|---|
| `POST /api/measurements` | collector-service |
| `GET /api/measurements/**` | storage-service |
| `/api/sensors/*/daily-summary` | analytics-service **(avant la règle suivante)** |
| `/api/sensors/**` | sensor-service |
| `/api/analytics/**` | analytics-service |

## À faire
1. Renommer `pom.xml.example` en `pom.xml`, choisir la version de Spring Cloud compatible avec votre Spring Boot, ajouter le module au pom parent.
2. Copier les routes dans `application.yml`.
3. Ajouter un service au Compose (profil `gateway`) et retirer la publication des ports 8081–8084.
4. Sécurité : Spring Security + JWT, rôles `ADMIN` (écriture capteurs) / `VIEWER` (lecture) —
   c'est l'extension **`EXT-12`** (CDC §18.3), généralement couplée à cette gateway.
