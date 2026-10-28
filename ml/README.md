# ml/ — pipeline de prévision (EXTENSION, étape 9C)

À démarrer **après** la validation du MVP. Entraînement en Python, export ONNX dans `../models/energy.onnx`, inférence en Java.

## Démarche (à documenter dans le rapport)
1. **Données** : exporter la série horaire (moyennes de `hourly_aggregates`) ; générer plusieurs jours avec le simulateur
   (`period-seconds` = 86400 pour un cycle réaliste, ou augmenter `duration`).
2. **Préparation** : valeurs manquantes (interpolation linéaire), normalisation min-max **calculée sur l'entraînement uniquement**,
   fenêtre d'entrée 24 h, horizon 1 à 24 h.
3. **Découpage chronologique** (jamais aléatoire) : entraînement / validation / test.
4. **Baselines** : persistance et saisonnier naïf — calculer MAE et RMSE : c'est la référence à battre.
5. **Modèle** : régression, réseau dense ou LSTM ; mesurer MAE et RMSE sur le jeu de test.
6. **Export ONNX** puis test d'inférence Java (cohérence des sorties avec Python).
7. **Limites** : taille de l'historique, saisonnalité, capteurs peu actifs, dérive.

`train_forecast.py` est un squelette commenté ; `requirements.txt` liste les dépendances pressenties (à valider).

## Extension (hors MVP)

Identifiant **`EXT-9C`** (étape 9C, plan J8) — *squelette livré, code non écrit*.
Ce dossier est la composante **entraînement** de l'extension ; la composante **inférence** vit dans
`analytics-service` (`forecast/ForecastService`, endpoint en 501 tant que désactivé).

- **Activation** : produire `../models/energy.onnx`, puis `ANALYTICS_FORECAST_ENABLED=true`.
- **Critère** : MAE/RMSE du modèle < baseline (persistance, saisonnier naïf) ; inférence Java cohérente avec Python.
- **Renvois** : CDC §18.1 · `docs/TODO-MAP.md` partie B · `analytics-service/README.md` · `models/README.md` · README racine §10.2.
