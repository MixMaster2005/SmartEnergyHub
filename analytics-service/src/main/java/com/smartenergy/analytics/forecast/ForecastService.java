package com.smartenergy.analytics.forecast;

// ========================================================================
// TODO(etape-9c-ext) - [EXTENSION] Prevision : baseline puis modele ONNX
// ========================================================================
// Guide : etape 9 niveau C et chapitre 18 (DJL / ONNX). CDC : decision D-18, section 6.5 (Prevision).
// A faire UNIQUEMENT apres validation du MVP (etape J7).
//
// Objectif : GET /api/analytics/{sensorId}/forecast?horizonHours=6 -> ForecastResponse.
//
// Demarche (sequencer comme dans le guide) :
//  1. BASELINE d'abord (BaselineForecaster) : persistance (derniere valeur horaire) et saisonnier naif
//     (meme heure la veille). Calculer MAE et RMSE sur un jeu de test : c'est la reference a battre.
//  2. Donnees : serie HORAIRE (moyennes de hourly_aggregates), fenetre d'entree 24 h, horizon 1 a 24 h.
//     Valeurs manquantes : interpolation lineaire. Normalisation min-max calculee sur l'ENTRAINEMENT uniquement.
//     Decoupage CHRONOLOGIQUE (jamais aleatoire) : entrainement / validation / test.
//  3. Entrainement hors Java (ml/train_forecast.py) puis export ONNX dans models/energy.onnx.
//  4. Inference en Java : ONNX Runtime Java (direct) ou DJL avec le moteur OnnxRuntime et un Translator adapte
//     (l'exemple du guide est simplifie). Charger le modele UNE fois au demarrage, pas a chaque requete.
//  5. Le modele n'a de valeur que s'il bat la baseline : comparer les metriques et le dire dans le rapport.
//  6. Feature flag : smartenergy.analytics.forecast.enabled=false -> ApiException(FORECAST_DISABLED) (501).
//  7. Metrique energy_forecast_latency_seconds.
// Rapport : fenetre, variables, valeurs manquantes, normalisation, metriques, limites du modele.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.analytics.ForecastResponse;
//
// public class ForecastService {
//     public ForecastResponse forecast(String sensorId, int horizonHours) {
//         // if (!props.forecast().enabled()) throw ApiException.of(ErrorCode.FORECAST_DISABLED, "...");
//         // lire la serie horaire, appliquer le modele ONNX (ou la baseline en repli), remplir metrics
//         throw new UnsupportedOperationException("TODO etape 9C (extension)");
//     }
// }
