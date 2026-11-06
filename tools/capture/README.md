# Captures et vérification de l'interface (EXT-UI)

Ce dossier contient `capture.mjs`, un script Playwright qui parcourt les six vues de
l'interface, vérifie qu'aucune exception JavaScript ni aucun état d'erreur ne s'affiche,
puis produit des captures pleine page et un rapport JSON.

Les captures servent de preuves pour la recette (T13/T14) et sont archivées dans
`preuves/captures/ext-ui/`.

## Prérequis

- Plateforme démarrée : `make up` (gateway sur `http://localhost:8080`).
- Node.js ≥ 20 et `npm`.
- Un navigateur :
  - soit Chromium fourni par Playwright : `npm run install-browsers` ;
  - soit Chrome déjà installé sur la machine : `npm run capture:chrome`
    (aucun téléchargement de navigateur).

## Installation

```bash
cd tools/capture
npm ci
# Option A — Chromium Playwright (téléchargement unique)
npm run install-browsers
# Option B — Chrome système, sans téléchargement
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci
```

## Exécution

```bash
# Chromium Playwright
node capture.mjs

# Chrome système
node capture.mjs --channel=chrome

# Une seule vue, avec un délai plus large
node capture.mjs --only=system --timeout=30000

# Autre cible et autre dossier de sortie
node capture.mjs --base-url=http://localhost:8080 --out=/tmp/captures-ui
```

Options : `--base-url`, `--out`, `--channel`, `--only`, `--headed`, `--timeout`.

## Ce qui est vérifié

Pour chaque vue (`dashboard`, `sensors`, `sensor-detail`, `analytics`, `system`,
`scenarios`) :

1. la page répond en HTTP < 400 ;
2. un sélecteur de disponibilité apparaît (cartes de santé, tableau, graphique…) ;
3. les chargements asynchrones sont terminés (`#app .loading` absent) ;
4. les compteurs minimaux sont atteints (5 services, ≥ 1 capteur, ≥ 3 graphiques,
   ≥ 10 scénarios) ;
5. aucun bloc `.empty--error` n'est affiché ;
6. aucune exception JavaScript (`pageerror`) ni erreur console inattendue.

Le 501 de la prévision (`FORECAST_DISABLED`, extension 9C) et les 404 de données
absentes sont des états documentés : ils sont tolérés, mais restent visibles dans
le rapport si le navigateur les journalise autrement.

## Sorties

```text
preuves/captures/ext-ui/
├── dashboard.png
├── sensors.png
├── sensor-detail.png
├── analytics.png
├── system.png
├── scenarios.png
└── capture-report.json
```

Le script sort avec le code 1 dès qu'une vue échoue, ce qui permet de l'utiliser
en CI. Le job GitHub `captures-ui` (déclenchement manuel) publie le dossier en
artefact.
