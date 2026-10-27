# Modeles IA exportes

Dossier monte en lecture seule dans `analytics-service` (`/app/models`).

- Format attendu : **ONNX** (ex. `energy.onnx`) produit par le pipeline `ml/` (extension `EXT-9C`, etape 9C).
- Chemin configure par `smartenergy.analytics.forecast.model-path` (defaut `models/energy.onnx`).
- Les `.onnx` sont ignores par Git (voir `.gitignore`) : versionnez-les explicitement (`git add -f`) si le sujet l'exige.
- Documenter pour chaque modele (dans `ml/README.md`) : fenetre d'entree, variables, valeurs manquantes,
  normalisation, metriques (MAE, RMSE vs baseline) et limites.

## Extension (hors MVP)

Identifiant **`EXT-9C`** (etape 9C) : ce dossier est un livrable de l'extension de prevision IA —
aucun modele n'existe encore (vide). Renvois : CDC section 18.1 · `docs/TODO-MAP.md` partie B ·
`ml/README.md` · README racine section 8.2.
