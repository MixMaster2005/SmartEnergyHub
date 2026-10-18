"""Squelette d'entraînement - EXTENSION (étape 9C). Voir ml/README.md.

Tout est a ecrire : chaque fonction decrit ce qu'elle doit faire.
"""

# def load_hourly_series(path):
#     """Lit la serie horaire d'un capteur (CSV exporte de hourly_aggregates). Retourne un DataFrame trie par heure."""
#
# def make_windows(series, window=24, horizon=6):
#     """Construit (X, y) : X = 24 heures passees, y = les 'horizon' heures suivantes. Interpoler les trous."""
#
# def chronological_split(X, y, train=0.7, val=0.15):
#     """Decoupage CHRONOLOGIQUE (pas de melange aleatoire). Normaliser avec les stats de l'entrainement SEULEMENT."""
#
# def baselines(X_test, y_test):
#     """Persistance et saisonnier naif : retourne MAE et RMSE. Reference a battre."""
#
# def train(model, X_train, y_train, X_val, y_val):
#     """Entrainement avec suivi de l'erreur de validation (arret precoce)."""
#
# def evaluate(model, X_test, y_test):
#     """MAE = mean(|y - yhat|) ; RMSE = sqrt(mean((y - yhat)^2))."""
#
# def export_onnx(model, path="../models/energy.onnx"):
#     """Export ONNX + verification : meme sortie qu'en Python sur quelques exemples."""

if __name__ == "__main__":
    raise SystemExit("A implementer : voir les fonctions ci-dessus et ml/README.md")
