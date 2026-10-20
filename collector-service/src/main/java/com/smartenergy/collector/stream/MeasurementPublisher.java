package com.smartenergy.collector.stream;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import com.smartenergy.platform.stream.StreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Publication d'une mesure dans le stream Redis (CDC section 5.3, decisions D-03, D-06). */
@Component
public class MeasurementPublisher {

    private static final String PUBLISHED_COUNTER = "energy_measurements_published_total";
    private static final String PUBLISH_TIMER = "energy_measurements_publish";

    private final StringRedisTemplate redis;
    private final StreamProperties props;
    private final MeterRegistry registry;
    private final Counter publishedCounter;
    private final Timer publishTimer;

    public MeasurementPublisher(StringRedisTemplate redis, StreamProperties props, MeterRegistry registry) {
        this.redis = redis;
        this.props = props;
        this.registry = registry;
        this.publishedCounter = registry.counter(PUBLISHED_COUNTER);
        this.publishTimer = Timer.builder(PUBLISH_TIMER).register(registry);
    }

    /**
     * Ecrit une entree dans le stream avec un MAXLEN approximatif (decision D-06) et compte la publication.
     *
     * @return l'id du message Redis (ex. 1700000000000-0)
     */
    public String publish(EnergyMeasurement measurement) {
        Timer.Sample sample = Timer.start(registry);
        try {
            Map<String, String> fields = MeasurementEventCodec.toFields(measurement);
            Record<String, ?> record = StreamRecords.string(fields).withStreamKey(props.name());
            XAddOptions options = XAddOptions.maxlen(props.maxLen()).approximateTrimming(true);
            RecordId id = redis.opsForStream().add(record, options);
            publishedCounter.increment();
            return id.getValue();
        } catch (DataAccessException e) {
            throw ApiException.unavailable("Redis indisponible", 5);
        } finally {
            sample.stop(publishTimer);
        }
    }
}
