# gateway-service — EXTENSION (non codé)

Point d'entrée unique en `:8080` (Spring Cloud Gateway) : routage, JWT, CORS, Swagger agrégé.

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
4. Sécurité : Spring Security + JWT, rôles `ADMIN` (écriture capteurs) / `VIEWER` (lecture).
