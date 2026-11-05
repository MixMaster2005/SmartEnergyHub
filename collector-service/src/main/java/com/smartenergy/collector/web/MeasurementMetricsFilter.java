package com.smartenergy.collector.web;

import com.smartenergy.contracts.common.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Compte les mesures recues par {@code POST /api/measurements} (CDC section 9) :
 * {@code energy_measurements_received_total{result=accepted|rejected}}.
 *
 * <p>Le compteur est incremente dans un {@code finally}, apres le passage dans la chaine :
 * il voit donc aussi les rejets produits <b>avant</b> le controleur (validation Jakarta,
 * JSON mal forme, 413 du {@link BodySizeLimitFilter}). C'est la raison pour laquelle ce filtre
 * est enregistre <b>avant</b> le filtre de taille de corps (ordre croissant).
 *
 * <p>Convention : toute reponse 2xx est {@code accepted} (le seul cas nominal est 202) ;
 * toute autre reponse (400, 413, 503) est {@code rejected}, c'est-a-dire une mesure qui
 * n'a pas ete publiee dans le stream. Le tag reste volontairement limite a deux valeurs
 * pour rester compatible avec le contrat du CDC.
 */
public class MeasurementMetricsFilter extends OncePerRequestFilter {

    /** Nom du compteur expose par Actuator / Prometheus. */
    public static final String RECEIVED_COUNTER = "energy_measurements_received_total";
    /** Tag distinguant les mesures acceptees des rejets. */
    public static final String RESULT_TAG = "result";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";

    private final MeterRegistry registry;

    public MeasurementMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !ApiPaths.MEASUREMENTS.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            int status = response.getStatus();
            String result = !failed && status >= 200 && status < 300 ? ACCEPTED : REJECTED;
            registry.counter(RECEIVED_COUNTER, RESULT_TAG, result).increment();
        }
    }
}
