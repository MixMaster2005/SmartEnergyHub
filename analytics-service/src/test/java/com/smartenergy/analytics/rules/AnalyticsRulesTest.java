package com.smartenergy.analytics.rules;

// ========================================================================
// TODO(tests) - Tests unitaires des regles analytiques
// ========================================================================
// Guide : strategie de tests (calculs d'energie, detection d'anomalies). A ecrire EN PREMIER (TDD) : AnalyticsRules est pure.
// Cas a couvrir :
//  - energie : puissance constante, pas de temps normal, trou de donnees plafonne, timestamp en retard (-> 0) ;
//  - seuil strict : P == Pmax n'est PAS une anomalie, P > Pmax l'est ;
//  - Welford : [1,2,3,4,5] -> mean 3, ecart-type 1.5811 ; n < minSamples -> pas de score ; ecart-type nul -> pas de score ;
//  - z(10) sur cet echantillon ~ 4.427 -> superieur au seuil 3.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.junit.jupiter.api.Test;
//
// class AnalyticsRulesTest {
//     @Test void energie_puissanceConstante_4kW_une_heure() { }
//     @Test void energie_trouDeDonnees_estPlafonne() { }
//     @Test void seuil_egalite_n_est_pas_une_anomalie() { }
//     @Test void welford_echantillon_1_a_5() { }
// }
