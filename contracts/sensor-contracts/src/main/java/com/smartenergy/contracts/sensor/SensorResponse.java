package com.smartenergy.contracts.sensor;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Representation d'un capteur (reponse des GET/POST/PUT de sensor-service). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SensorResponse(
        String sensorId,
        String name,
        String building,
        Integer floor,
        double maxPowerKw,
        boolean active,
        List<String> tags,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
