package com.smartenergy.collector.web;

import com.smartenergy.collector.stream.MeasurementPublisher;
import com.smartenergy.collector.validation.MeasurementTimeValidator;
import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.common.HeaderNames;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.platform.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ingestion des mesures : CDC section 5.2 et 7.2. */
@RestController
@RequestMapping(ApiPaths.MEASUREMENTS)
@Tag(name = "Mesures", description = "Ingestion des mesures d'energie par les capteurs")
public class MeasurementController {

    private static final Logger log = LoggerFactory.getLogger(MeasurementController.class);

    private final MeasurementPublisher publisher;
    private final MeasurementTimeValidator timeValidator;

    public MeasurementController(MeasurementPublisher publisher, MeasurementTimeValidator timeValidator) {
        this.publisher = publisher;
        this.timeValidator = timeValidator;
    }

    @PostMapping
    @Operation(summary = "Ingerer une mesure",
            description = "202 accepte avec en-tete X-Event-Id (identifiant deterministe) ; "
                    + "400 VALIDATION_ERROR, MEASUREMENT_TOO_OLD ou MEASUREMENT_IN_FUTURE ; "
                    + "503 BROKER_UNAVAILABLE + Retry-After si le broker Redis est indisponible.")
    public ResponseEntity<Void> receive(@Valid @RequestBody EnergyMeasurementRequest request) {
        timeValidator.check(request.timestamp());
        EnergyMeasurement m = EnergyMeasurement.from(request, RequestIdFilter.currentRequestId(), Instant.now());
        String redisId = publisher.publish(m);
        log.info("Mesure acceptee sensorId={} eventId={} redisId={}", m.sensorId(), m.eventId(), redisId);
        return ResponseEntity.accepted().header(HeaderNames.EVENT_ID, m.eventId().toString()).build();
    }
}
