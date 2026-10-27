package com.smartenergy.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartenergy.collector.web.BodySizeLimitFilter;
import com.smartenergy.contracts.common.ApiPaths;
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
     * Limite de taille du corps de {@code POST /api/measurements} -> 413 PAYLOAD_TOO_LARGE.
     *
     * <p>Ordre {@code HIGHEST_PRECEDENCE + 1} : immediatement apres {@code RequestIdFilter}
     * (platform-web, enregistre en {@code HIGHEST_PRECEDENCE}) — sans ce rang, le filtre
     * s'executerait avant que le traceId existe et la reponse 413 serait sans correlation.
     * La restriction de chemin est doublonnee par {@code shouldNotFilter} (POST uniquement).
     */
    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(
            MeasurementProperties props, ObjectMapper objectMapper) {
        FilterRegistrationBean<BodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(props, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns(ApiPaths.MEASUREMENTS);
        return registration;
    }
}
