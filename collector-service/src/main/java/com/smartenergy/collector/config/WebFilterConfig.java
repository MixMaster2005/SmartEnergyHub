package com.smartenergy.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.collector.web.BodySizeLimitFilter;
import com.smartenergy.collector.web.MeasurementMetricsFilter;
import com.smartenergy.contracts.common.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Filtres HTTP propres au collector (etape 12 : validation des entrees, CDC sections 5.2 et 10).
 */
@Configuration
public class WebFilterConfig {

    /**
     * Compteur {@code energy_measurements_received_total{result}} (CDC section 9).
     *
     * <p>Ordre {@code HIGHEST_PRECEDENCE + 1} : enveloppe le filtre de taille de corps pour
     * compter aussi les 413. Il reste apres {@code RequestIdFilter}
     * ({@code HIGHEST_PRECEDENCE}) afin que le traceId soit deja present.
     */
    @Bean
    FilterRegistrationBean<MeasurementMetricsFilter> measurementMetricsFilter(MeterRegistry registry) {
        FilterRegistrationBean<MeasurementMetricsFilter> registration =
                new FilterRegistrationBean<>(new MeasurementMetricsFilter(registry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns(ApiPaths.MEASUREMENTS);
        return registration;
    }

    /**
     * Limite de taille du corps de {@code POST /api/measurements} -> 413 PAYLOAD_TOO_LARGE.
     *
     * <p>Ordre {@code HIGHEST_PRECEDENCE + 2} : apres {@code RequestIdFilter} (traceId) et
     * apres le filtre de metriques, qui doit voir le 413. Sans ce rang, la reponse 413 serait
     * sans correlation et ne serait pas comptee. La restriction de chemin est doublonnee par
     * {@code shouldNotFilter} (POST uniquement).
     */
    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(
            MeasurementProperties props, ObjectMapper objectMapper) {
        FilterRegistrationBean<BodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(props, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.addUrlPatterns(ApiPaths.MEASUREMENTS);
        return registration;
    }
}
