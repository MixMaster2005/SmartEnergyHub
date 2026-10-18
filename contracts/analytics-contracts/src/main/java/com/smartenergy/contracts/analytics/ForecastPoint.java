package com.smartenergy.contracts.analytics;

import java.time.Instant;

/** Puissance moyenne prevue pour l'heure commencant a {@code hour}. */
public record ForecastPoint(Instant hour, double powerKw) {}
