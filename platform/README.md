# Modules de plomberie partagée

| Module | Rôle | État |
|---|---|---|
| `platform-web` | Auto-configuration : filtre `X-Request-Id` (MDC `traceId`), `GlobalExceptionHandler` (RFC 9457 + `errors[]`, 503 avec `Retry-After`, 409 sur conflit de version), `GET /api/health`, OpenAPI, Actuator + Prometheus | ✅ |
| `platform-stream` | `StreamProperties` (`smartenergy.stream.*`) ; `AbstractStreamConsumer` (boucle XREADGROUP / ACK / reprise XPENDING+XCLAIM / DLQ) ✅ ; `StreamLagMetrics` (métriques `energy_stream_pending{group}` et `energy_stream_lag{group}`, rafraîchies toutes les 5 s — étape 11) ✅ | ✅ |

Pour en bénéficier, un service ajoute simplement la dépendance : l'auto-configuration fait le reste (aucune annotation).
Les services lèvent `ApiException(ErrorCode, ...)` ; le handler produit `application/problem+json` avec `code` et `traceId`.
Un service qui consomme le stream (`smartenergy.stream.group` défini) expose automatiquement les métriques
`energy_stream_pending` et `energy_stream_lag` (étape 11), vérifiables sur
`GET :808x/actuator/metrics/energy_stream_pending` ; le collector, qui publie sans consommer, ne les a pas.

## Extension (hors MVP)

`platform-stream` fournit les métriques de base de l'extension **`EXT-11`** (CDC §18.2) : elles sont
déjà exposées en `/actuator/prometheus`, il manque Prometheus + Grafana. Renvois : CDC §18.2 ·
`docs/TODO-MAP.md` partie B · README racine §10.2.
