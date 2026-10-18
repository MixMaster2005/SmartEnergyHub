package com.smartenergy.analytics.client;

import com.smartenergy.analytics.config.AnalyticsProperties;
import com.smartenergy.contracts.measurement.RedisKeys;
import com.smartenergy.contracts.sensor.SensorResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Seuil d'un capteur pour analytics : cache Redis (60 s) -> sensor-service -> seuil par defaut.
 * Ne leve JAMAIS d'exception : l'analyse doit continuer meme si sensor-service ou Redis sont indisponibles.
 */
@Component
public class SensorClient {

    private static final Logger log = LoggerFactory.getLogger(SensorClient.class);

    public record Threshold(double maxPowerKw, boolean active, Source source) {
        public enum Source { SENSOR, DEFAULT }
    }

    private final SensorGateway gateway;
    private final StringRedisTemplate redis;
    private final AnalyticsProperties props;

    public SensorClient(SensorGateway gateway, StringRedisTemplate redis, AnalyticsProperties props) {
        this.gateway = gateway;
        this.redis = redis;
        this.props = props;
    }

    public Threshold thresholdFor(String sensorId) {
        String key = RedisKeys.cacheSensor(sensorId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return parse(cached);
        } catch (RuntimeException e) {
            log.warn("Cache Redis indisponible ({}), appel direct", e.toString());
        }
        Optional<SensorResponse> sensor = gateway.fetch(sensorId);
        if (sensor.isPresent()) {
            Threshold t = new Threshold(sensor.get().maxPowerKw(), sensor.get().active(), Threshold.Source.SENSOR);
            try {
                redis.opsForValue().set(key, t.maxPowerKw() + "|" + t.active(), RedisKeys.CACHE_SENSOR_TTL);
            } catch (RuntimeException e) {
                log.warn("Ecriture du cache impossible : {}", e.toString());
            }
            return t;
        }
        return new Threshold(props.defaultMaxPowerKw(), true, Threshold.Source.DEFAULT);
    }

    private static Threshold parse(String cached) {
        String[] parts = cached.split("\\|");
        return new Threshold(Double.parseDouble(parts[0]), Boolean.parseBoolean(parts[1]), Threshold.Source.SENSOR);
    }
}
