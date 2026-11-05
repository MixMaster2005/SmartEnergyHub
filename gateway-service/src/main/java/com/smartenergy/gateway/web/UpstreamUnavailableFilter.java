package com.smartenergy.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Traduit une indisponibilite du service amont (connexion refusee, route reseau absente,
 * coupure DNS...) en {@code 503 Service Unavailable} au format {@code application/problem+json}.
 *
 * <p>Sans ce filtre, Spring Cloud Gateway 4.3 laisse remonter l'exception d'E/S brute et
 * repond {@code 500}, ce qui laisse croire a un bug de la passerelle. Le statut 503 est
 * coherent avec le reste de la plateforme (Redis indisponible cote collector) et permet a
 * l'appelant de reessayer.
 *
 * <p>Le filtre s'execute autour de toute la chaine de routage ; il ne touche pas aux
 * reponses deja commencees (impossible d'y changer le statut).
 */
@Component
public class UpstreamUnavailableFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UpstreamUnavailableFilter.class);

    private final ObjectMapper objectMapper;

    public UpstreamUnavailableFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).onErrorResume(IOException.class, ex -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(ex);
            }
            log.warn("Service amont indisponible : {} {} ({})",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex.getMessage());
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "about:blank");
            body.put("title", "Service temporairement indisponible");
            body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
            body.put("code", "UPSTREAM_UNAVAILABLE");
            body.put("detail", "Le service amont ne repond pas ; reessayez dans quelques secondes");
            try {
                byte[] json = objectMapper.writeValueAsBytes(body);
                return response.writeWith(Mono.just(response.bufferFactory().wrap(json)));
            } catch (IOException serializationFailure) {
                return Mono.error(serializationFailure);
            }
        });
    }

    @Override
    public int getOrder() {
        // Autour du routage : l'exception d'E/S remonte jusqu'ici avant le handler d'erreur.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
