package com.smartenergy.analytics.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Coeur metier analytics : energie, depassement de seuil et score z (CDC section 6.5, decisions
 * D-13 et D-14). Guide : etape 9 (niveaux A et B), formules (2), (3) et (4).
 *
 * <p>Classe STRICTEMENT PURE : aucun Spring, aucune base, aucun effet de bord. Chaque fonction est
 * deterministe et testable isolément ({@code AnalyticsRulesTest}) ; c'est le livrable « calculs
 * d'energie, detection d'anomalies » de l'etape 6 du plan (J6).
 *
 * <ul>
 *   <li><b>Energie (rectangle a gauche)</b> : pour une mesure a l'instant t,
 *       {@code E += P(precedente) x dt} avec dt en heures = ecart reel avec la mesure precedente du
 *       capteur, <b>plafonne</b> a {@code expectedIntervalSeconds x gapFactor} pour ne pas compter
 *       les trous de donnees. Une mesure dont le timestamp est inferieur ou egal au precedent
 *       (rejeu, retard) rapporte 0 : l'energie n'est jamais comptee deux fois (count/min/max sont
 *       de toute facon mis a jour par l'agregat).</li>
 *   <li><b>Seuil</b> : depassement STRICT, {@code powerKw > maxPowerKw} (comme {@code Pt > Pmax}
 *       du guide) ; l'egalite n'est pas une anomalie. Seuil par defaut 10 kW si capteur inconnu
 *       (voir {@code SensorClient}).</li>
 *   <li><b>Score z</b> : statistiques en ligne de Welford (etaat {@code (n, mean, m2)} stocke dans
 *       {@code sensor_state}), ecart-type d'echantillon {@code sqrt(m2 / (n - 1))} pour
 *       {@code n >= 2}. Le score n'est produit que si {@code n >= minSamples} et {@code stddev > 0}
 *       ; anomalie ZSCORE si {@code |z| > 3} (parametre). Regle essentielle : les mesures DEJA
 *       anormales ne mettent PAS a jour l'etat, sinon mu et sigma sont pollues par le pic.</li>
 *   <li><b>Severites</b> : THRESHOLD = HIGH, ZSCORE = MEDIUM (CDC 6.5).</li>
 * </ul>
 *
 * <p><b>Limits documentees</b> : un profil sinusoïdal gonfle sigma (compromis simplicite /
 * precision, variante par tranche horaire = amelioration possible) ; un fort desordre temporel
 * fausse legerement l'energie (un recalcul nocturne depuis Cassandra est envisageable, D-13).
 */
public final class AnalyticsRules {

    private AnalyticsRules() {}

    /**
     * Etat statistique d'un capteur pour le score z, immuable : la mise a jour renvoie un nouvel
     * exemplaire (D-13, {@code sensor_state} = n, mean, m2).
     */
    public record Welford(long n, double mean, double m2) {

        public static Welford empty() {
            return new Welford(0, 0.0, 0.0);
        }

        /**
         * Ajoute un echantillon (algorithme de Welford) : n' = n + 1 ; delta = x - mean ;
         * mean' = mean + delta / n' ; m2' = m2 + delta * (x - mean').
         */
        public Welford add(double x) {
            long nPlusOne = n + 1;
            double delta = x - mean;
            double meanPrime = mean + delta / nPlusOne;
            double m2Prime = m2 + delta * (x - meanPrime);
            return new Welford(nPlusOne, meanPrime, m2Prime);
        }

        /** Ecart-type d'echantillon {@code sqrt(m2 / (n - 1))}, 0 si moins de deux echantillons. */
        public double stddev() {
            return n >= 2 ? Math.sqrt(m2 / (n - 1)) : 0.0;
        }
    }

    /**
     * Increment d'energie (kWh) du rectangle a gauche entre la mesure precedente du capteur et la
     * mesure courante : {@code P(precedente) x dt}, dt en heures.
     *
     * @param previousTs timestamp de la mesure precedente du capteur
     * @param previousPowerKw puissance (kW) de cette mesure precedente
     * @param currentTs timestamp de la mesure courante
     * @param expectedIntervalSeconds intervalle nominal des capteurs (secondes)
     * @param gapFactor plafond du pas de temps, en multiples de l'intervalle attendu
     * @return 0.0 si le temps n'avance pas (rejeu ou retard) ou si la mesure precedente est absente
     */
    public static double energyIncrementKwh(Instant previousTs, double previousPowerKw, Instant currentTs,
                                            int expectedIntervalSeconds, int gapFactor) {
        if (previousTs == null) {
            return 0.0; // pas de mesure precedente connue : aucun pas de temps a integrer
        }
        Duration gap = Duration.between(previousTs, currentTs);
        if (gap.isNegative() || gap.isZero()) {
            return 0.0; // rejeu ou retard : on ne compte pas deux fois
        }
        long maxGapSeconds = (long) expectedIntervalSeconds * gapFactor;
        long gapSeconds = Math.min(gap.toSeconds(), maxGapSeconds); // plafond des trous de donnees
        double dtHours = gapSeconds / 3600.0;
        return previousPowerKw * dtHours;
    }

    /** Depassement de seuil STRICT : P == Pmax n'est pas une anomalie (Pt > Pmax du guide). */
    public static boolean isThresholdExceeded(double powerKw, double maxPowerKw) {
        return powerKw > maxPowerKw;
    }

    /**
     * Score z de l'echantillon x dans l'etat courant.
     *
     * @return (x - mean) / stddev, ou {@code null} si l'etat est trop court ({@code n < minSamples})
     *         ou si l'ecart-type est nul (serie constante : rien d'anormal a detecter)
     */
    public static Double zScore(Welford state, double x, int minSamples) {
        if (state.n() < minSamples) {
            return null;
        }
        double stddev = state.stddev();
        if (stddev == 0.0) {
            return null;
        }
        return (x - state.mean()) / stddev;
    }
}
