package com.smartenergy.analytics.client;

import com.smartenergy.contracts.sensor.SensorResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Appel REST protege vers sensor-service : GET /api/sensors/{id} (CDC section 5.5).
 * Separe de SensorClient car les annotations Resilience4j ne s'appliquent qu'a travers le proxy Spring.
 */
@Component
public class SensorGateway {

    private static final Logger log = LoggerFactory.getLogger(SensorGateway.class);

    private final RestClient client;

    public SensorGateway(RestClient sensorRestClient) {
        this.client = sensorRestClient;
    }

    /** @return le capteur, ou vide s'il est inconnu (404) ou si sensor-service est indisponible. */
    @Retry(name = "sensor-service")
    @CircuitBreaker(name = "sensor-service", fallbackMethod = "unavailable")
    public Optional<SensorResponse> fetch(String sensorId) {
        try {
            return Optional.ofNullable(client.get().uri("/api/sensors/{id}", sensorId).retrieve().body(SensorResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public Optional<SensorResponse> unavailable(String sensorId, Throwable cause) {
        log.warn("sensor-service indisponible pour {} ({}) : seuil par defaut utilise", sensorId, cause.toString());
        return Optional.empty();
    }
}
