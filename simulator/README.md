# simulator

**Rôle** : source de données contrôlée (EF-01). Application en ligne de commande (sans serveur web) qui envoie, pour N capteurs,
une mesure par pas de temps au collector, puis affiche un résumé (envoyées / acceptées 202 / rejetées 4xx / erreurs).

Modèle : `P(t) = Pbase + A·sin(2πt/T + phase) + bruit gaussien` (phase et `Pbase` propres à chaque capteur, graine `seed` reproductible).

## Lancer
```bash
docker compose run --rm simulator                                   # 5 capteurs, 2 minutes
docker compose run --rm simulator \
  --smartenergy.simulator.anomaly.sensor=SENSOR-003 \
  --smartenergy.simulator.invalid-rate=0.05 --smartenergy.simulator.duplicate-rate=0.02
```

## Paramètres (`smartenergy.simulator.*`)
| Paramètre | Défaut | Rôle |
|---|---|---|
| `target-url` / `sensor-service-url` | collector :8081 / sensor :8084 | cibles |
| `register-sensors` | false (true dans Compose) | enregistre `SENSOR-001..N` dans sensor-service (409 ignoré) |
| `sensors`, `interval`, `duration` | 5, PT2S, PT2M | `PT0S` = sans fin |
| `seed` | 42 | reproductibilité |
| `p-base-kw`, `amplitude-kw`, `period-seconds`, `noise-sigma-kw` | 3.5, 1.0, 120, 0.1 | modèle de puissance (période comprimée) |
| `voltage-base`, `temperature-base` | 230, 25 | bases des mesures secondaires |
| `max-power-kw` | 8.0 | seuil des capteurs enregistrés (doit être < `factor` × `Pbase`) |
| `anomaly.sensor`, `at-seconds`, `duration-seconds`, `factor` | vide, 60, 10, 3.0 | pic injecté |
| `invalid-rate`, `duplicate-rate` | 0 | mesures invalides / doublons (scénarios 2 et 8) |

Un 4xx est compté « rejeté » sans réessai ; un 503 ou une erreur réseau est retenté 3 fois avec délai croissant.

## Extension (hors MVP)

Renvois : `EXT-9C` (CDC §18.1) — le simulateur sert à **générer l'historique d'entraînement**
(`period-seconds` = 86400, durée multi-jours) ; `EXT-12` (CDC §18.3) — prévoir l'envoi d'une clé d'API
si `POST /api/measurements` est protégé. Voir `docs/cdc.md` §18, `docs/TODO-MAP.md` partie B et
README racine §8.2.
