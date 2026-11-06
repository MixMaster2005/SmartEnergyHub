# gateway-service — `EXT-GW` (livré)

Point d'entrée unique optionnel (Spring Cloud Gateway, WebFlux) : routage, Swagger
agrégé, CORS et accueil de l'interface **EXT-UI**. JWT/rôles et limitation de débit
restent dans l'extension `EXT-12`.

| Gabarit CDC §18.4 | |
|---|---|
| **État** | livré (D-25 à D-27) — module Maven, profil Compose `gateway`, tests de recette ; sert l'interface EXT-UI (D-28 à D-30) |
| **Exigences** | CDC §7.6, D-16 (versioning d'URL), D-17 (routage du `daily-summary`) |
| **Activation** | `make up` (profil `gateway` explicite), ou `docker compose --profile gateway up -d --build` |
| **Critère** | 5 routes métier via `:8080` avec l'ordre D-17, Swagger agrégé, interface EXT-UI + CORS, `make e2e` vert |
| **Renvois** | CDC §18.4 et §18.6 · `docs/decisions.md` (D-25 à D-30) · `docs/TODO-MAP.md` partie B · `frontend/README.md` |

## Démarrage

```bash
make up                       # gateway incluse (:8080)
./scripts/e2e.sh              # vérifications des scénarios 1–11, 413, contrôles gateway et EXT-UI
# ou, service seul (les 4 services doivent tourner sur 8081-8084) :
mvn -pl gateway-service -am package -DskipTests
java -jar gateway-service/target/app.jar
```

Variables lues (12-factor) :

| Variable | Défaut local | Compose |
|---|---|---|
| `COLLECTOR_URI` | `http://localhost:8081` | `http://collector-service:8080` |
| `STORAGE_URI` | `http://localhost:8082` | `http://storage-service:8080` |
| `ANALYTICS_URI` | `http://localhost:8083` | `http://analytics-service:8080` |
| `SENSOR_URI` | `http://localhost:8084` | `http://sensor-service:8080` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | idem, surchargeable |
| `SMARTENERGY_FRONTEND_DIR` | `/app/frontend` (repli classpath) | `/app/frontend` (volume `./frontend`) |

## Routes métier (l'ordre compte, D-17)

L'ordre est explicite (`order: 0..4`) : `daily-summary` passe **avant** la règle
générale `/api/sensors/**`.

| Ordre | Prédicat | Cible |
|---|---|---|
| 0 | `POST /api/measurements` | collector-service |
| 1 | `GET /api/measurements/**` | storage-service |
| 2 | `GET /api/sensors/*/daily-summary` | analytics-service |
| 3 | `/api/sensors/**` | sensor-service |
| 4 | `/api/analytics/**` | analytics-service |

## Routes d'exploitation (extension D-26)

Le CDC §7.6 ne couvre que `/api/**`. Ces préfixes gardent readiness, `/api/health` et
Actuator joignables depuis l'origine unique ; le préfixe est retiré (`StripPrefix=1`).

| Prédicat | Cible | Exemple |
|---|---|---|
| `/collector/**` | collector-service | `/collector/actuator/health/readiness` |
| `/storage/**` | storage-service | `/storage/api/health` |
| `/analytics/**` | analytics-service | `/analytics/api/health` |
| `/sensor/**` | sensor-service | `/sensor/actuator/health/readiness` |

## Swagger agrégé et accueil

| URL gateway | Cible |
|---|---|
| `GET /swagger-ui.html` | UI springdoc, 4 APIs listées |
| `/v3/api-docs/collector` | `collector-service:/v3/api-docs` |
| `/v3/api-docs/storage` | `storage-service:/v3/api-docs` |
| `/v3/api-docs/analytics` | `analytics-service:/v3/api-docs` |
| `/v3/api-docs/sensor` | `sensor-service:/v3/api-docs` |
| `GET /` | interface **EXT-UI** : `frontend/index.html` (volume `./frontend`, repli classpath `static/`) |

CORS : origines `CORS_ALLOWED_ORIGINS`, méthodes GET/POST/PUT/PATCH/DELETE/OPTIONS,
en-têtes `*`, exposition de `X-Request-Id`, `X-Event-Id`, `Retry-After`.

## Comportement en panne

- Service amont injoignable (connexion refusée, route réseau absente, DNS) :
  **503** `application/problem+json` avec `code=UPSTREAM_UNAVAILABLE` et `Retry-After: 5`
  (`UpstreamUnavailableFilter`), au lieu du 500 brut de Spring Cloud Gateway.
- Route inconnue : 404 standard WebFlux.
- Aucune donnée n'est mise en cache par la gateway : elle ne modifie pas les garanties
  de livraison du stream (D-05).

## Hors périmètre (assumé)

- JWT/rôles `ADMIN`/`VIEWER`, clé d'API, limitation de débit : `EXT-12` (CDC §18.3).
- TLS : reverse proxy, hors dépôt.
- SPA-fallback (`/*` → `index.html`) : inutile avec le routage par hash de l'interface
  (D-30) ; la gateway ne sert que `/` et les fichiers statiques.
- Les ports 8081–8084 restent publiés : la gateway est additive, pas un remplacement (D-25).
