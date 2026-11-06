# Interface Web EXT-UI — SmartEnergy Hub

Application statique de consultation de la plateforme, servie par la gateway sur
`http://localhost:8080/`. Aucune étape de build : ce sont des modules ES natifs,
exécutés directement par le navigateur.

## Principes

- **Zéro build** : pas de bundler, pas de transpilation, pas de dépendance npm en
  production. Les fichiers sont servis tels quels.
- **Zéro CDN** : Chart.js 4.4.7 est vendorisé (`vendor/chart.umd.js`, licence MIT
  dans `vendor/CHART-LICENSE.md`). L'interface fonctionne hors ligne.
- **Zéro CORS** : toutes les URL sont relatives à l'origine de la page (la gateway),
  qui sert à la fois les fichiers statiques et les routes `/api/**`.
- **Lecture seule**, à une exception près : la page Scénarios écrit dans le capteur
  de recette réservé `UI-TEST-001`, jamais dans les capteurs du simulateur.
- **UTC partout** : les horodatages sont affichés en UTC (comme les preuves) ;
  l'heure locale est fournie en infobulle.

## Arborescence

```text
frontend/
├── index.html            coquille, navigation, en-tête de santé
├── styles.css            thème clair, responsive, états vides/erreurs
├── app.js                point d'entrée : routeur, poller de santé, erreurs globales
├── api.js                client HTTP (fetch, timeout, annulation, tags Actuator)
├── errors.js             décodage Problem Details et messages
├── format.js             formatage UTC, nombres, libellés, échappement HTML
├── metrics.js            lecture Actuator, RateTracker, RingBuffer
├── ranges.js             presets et garde-fous de plages temporelles
├── poller.js             sondage périodique sans chevauchement (visibilité, backoff)
├── router.js             routage par hash (`#/sensors/SENSOR-001`)
├── ui.js                 badges, cartes, états vides, toasts, téléchargements
├── report.js             rapport Markdown/JSON T01–T14 (pur, testé)
├── views/
│   ├── dashboard.js      santé, capteurs, flux Redis, débit
│   ├── sensors.js        liste filtrable + fiche (Chart.js, anomalies)
│   ├── analytics.js      résumé horaire, résumé quotidien, anomalies, prévision
│   ├── system.js         métriques Actuator et courbes 15 min
│   └── scenarios.js      scénarios T01–T14, manipulations guidées, export
├── vendor/
│   ├── chart.umd.js      Chart.js 4.4.7 (MIT)
│   └── CHART-LICENSE.md
└── tests/
    ├── *.test.js         84 tests `node --test`
    └── fixtures/         réponses réelles capturées sur la plateforme
```

## Routes

| Hash | Vue | Données |
|---|---|---|
| `#/dashboard` | Tableau de bord | `/actuator/health`, `/<service>/actuator/health`, `/api/sensors`, `/api/analytics/exceedances?threshold=-1`, métriques |
| `#/sensors` | Liste des capteurs | `/api/sensors` |
| `#/sensors/{id}` | Fiche capteur | `/api/sensors/{id}`, `/api/measurements/{id}/latest`, `/api/measurements/{id}`, `/api/analytics/{id}/anomalies` |
| `#/analytics` | Analyses | `/api/analytics/{id}/summary`, `/daily-summary`, `/anomalies`, `/forecast` |
| `#/system` | Système | `/<service>/actuator/metrics/{name}` (lag, pending, débits, latence, anomalies) |
| `#/scenarios` | Scénarios | `POST /api/measurements`, `/api/sensors`, historique, anomalies, OpenAPI |

Le routeur est en hash : le serveur ne voit que `/`, donc un rafraîchissement ne
produit jamais de 404 et aucune règle de réécriture n'est nécessaire.

## Conventions d'affichage

- **États vides ≠ erreurs** : `404 NO_DATA` et `404 MEASUREMENT_NOT_FOUND` sont
  rendus comme des états vides ; `501 FORECAST_DISABLED` est une limite documentée
  (extension 9C) ; les autres codes affichent un bloc d'erreur avec `code` et `traceId`.
- **Compteurs cumulatifs** : un delta négatif (redémarrage d'un service) affiche
  `n/d` au lieu d'un débit aberrant.
- **Fraîcheur** : au-delà de 30 s sans mesure, un capteur est signalé « inactif > 30 s ».
- **Échappement** : toute valeur venue de l'API passe par `escapeHtml` avant
  insertion dans un template HTML.

## Tests

```bash
cd frontend
npm test            # ou, depuis la racine : make ui-test
```

Les tests couvrent les modules purs (erreurs, format, métriques, plages, routeur,
rapport) et valident la forme des fixtures réelles (capteurs, historique, résumé,
anomalies, métriques Actuator). Aucun test ne dépend du DOM ; `app.js` est chargé
par le navigateur et vérifié par les captures Playwright.

## Captures

```bash
make captures       # outils Playwright, plateforme démarrée
```

Voir `tools/capture/README.md`. Les preuves sont écrites dans
`preuves/captures/ext-ui/`.

## Ajouter une vue

1. Créer `views/ma-vue.js` exportant `render({ mount, params, query, signal, navigate })`
   et retournant éventuellement `{ destroy }`.
2. Ajouter la route dans `app.js`.
3. Ajouter le lien dans la navigation de `index.html` (`data-nav`).
4. Ajouter la vue à `tools/capture/capture.mjs` (sélecteur de disponibilité et
   contrôles) et à `frontend/tests/modules.test.js`.

## Police

L'interface utilise IBM Plex Sans (graisses 400, 500, 600), vendorisée dans `vendor/fonts/` pour fonctionner hors ligne. Licence SIL Open Font License 1.1 : voir `vendor/fonts/PLEX-LICENSE.txt`.
