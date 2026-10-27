package com.smartenergy.analytics.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartenergy.analytics.rules.AnalyticsRules.Welford;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires des regles analytiques (CDC 6.5, formules 2 a 4). Jeu de reference de
 * l'en-tete du guide : energie, seuil strict, Welford et score z verifies a la main.
 */
class AnalyticsRulesTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    // ---------------------------------------------------------------- energie

    @Test
    void energie_puissanceConstante_4kW_une_heure() {
        // 4 kW toutes les 2 s pendant 1 h : 1800 pas de 2 s = 1800 x 4 x (2/3600) = 4.0 kWh
        Instant cursor = T0;
        double total = 0.0;
        for (int i = 0; i < 1800; i++) {
            Instant next = cursor.plus(Duration.ofSeconds(2));
            total += AnalyticsRules.energyIncrementKwh(cursor, 4.0, next, 2, 5);
            cursor = next;
        }
        assertEquals(4.0, total, 1e-6);
    }

    @Test
    void energie_trouDeDonnees_estPlafonne() {
        // 10 min sans mesure -> pas de temps plafonne a 10 s (2 s x 5), pas 600 s
        Instant previous = T0;
        Instant current = T0.plus(Duration.ofMinutes(10));
        double increment = AnalyticsRules.energyIncrementKwh(previous, 4.0, current, 2, 5);
        assertEquals(4.0 * 10 / 3600.0, increment, 1e-12);
    }

    @Test
    void energie_pasDeTempsNormal() {
        double increment = AnalyticsRules.energyIncrementKwh(T0, 3.5, T0.plusSeconds(2), 2, 5);
        assertEquals(3.5 * 2 / 3600.0, increment, 1e-12);
    }

    @Test
    void energie_timestampEnRetard_ouIdentique_retourneZero() {
        assertEquals(0.0, AnalyticsRules.energyIncrementKwh(T0, 4.0, T0.minusSeconds(2), 2, 5));
        assertEquals(0.0, AnalyticsRules.energyIncrementKwh(T0, 4.0, T0, 2, 5));
    }

    // ------------------------------------------------------------------ seuil

    @Test
    void seuil_egalite_n_est_pas_une_anomalie() {
        assertFalse(AnalyticsRules.isThresholdExceeded(10.0, 10.0));
        assertTrue(AnalyticsRules.isThresholdExceeded(10.1, 10.0));
        assertFalse(AnalyticsRules.isThresholdExceeded(9.9, 10.0));
    }

    // ---------------------------------------------------------------- Welford

    @Test
    void welford_echantillon_1_a_5() {
        Welford state = Welford.empty();
        for (double x : new double[] {1, 2, 3, 4, 5}) {
            state = state.add(x);
        }
        assertEquals(5, state.n());
        assertEquals(3.0, state.mean(), 1e-12);
        // ecart-type d'echantillon : sqrt(10 / 4) = 1.5811...
        assertEquals(Math.sqrt(2.5), state.stddev(), 1e-12);
    }

    // ---------------------------------------------------------------- score z

    @Test
    void zScore_10_sur_echantillon_1_a_5_est_anormal() {
        Welford state = Welford.empty();
        for (double x : new double[] {1, 2, 3, 4, 5}) {
            state = state.add(x);
        }
        Double z = AnalyticsRules.zScore(state, 10.0, 5); // minSamples ramene a 5 pour ce petit jeu
        assertNotNull(z);
        // (10 - 3) / 1.5811 = 4.427 -> |z| > 3 (le seuil de decision est applique par le consumer)
        assertEquals(4.427, z, 1e-3);
    }

    @Test
    void zScore_moinsDeMinSamples_pasDeScore() {
        Welford state = Welford.empty();
        for (double x : new double[] {1, 2, 3}) {
            state = state.add(x);
        }
        assertNull(AnalyticsRules.zScore(state, 10.0, 30));
    }

    @Test
    void zScore_ecartTypeNul_pasDeScore() {
        Welford state = Welford.empty();
        for (int i = 0; i < 40; i++) {
            state = state.add(4.0); // serie constante : sigma = 0
        }
        assertNull(AnalyticsRules.zScore(state, 9.9, 30));
    }
}
