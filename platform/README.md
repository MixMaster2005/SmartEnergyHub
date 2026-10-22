# Modules de plomberie partagée

| Module | Rôle | État |
|---|---|---|
| `platform-web` | Auto-configuration : filtre `X-Request-Id` (MDC `traceId`), `GlobalExceptionHandler` (RFC 9457 + `errors[]`, 503 avec `Retry-After`, 409 sur conflit de version), `GET /api/health`, OpenAPI, Actuator + Prometheus | ✅ |
| `platform-stream` | `StreamProperties` (`smartenergy.stream.*`) ; `AbstractStreamConsumer` (boucle XREADGROUP / ACK / reprise XPENDING+XCLAIM / DLQ) ✅ ; `StreamLagMetrics` **à implémenter** 🟡 | 🟡 |

Pour en bénéficier, un service ajoute simplement la dépendance : l'auto-configuration fait le reste (aucune annotation).
Les services lèvent `ApiException(ErrorCode, ...)` ; le handler produit `application/problem+json` avec `code` et `traceId`.
