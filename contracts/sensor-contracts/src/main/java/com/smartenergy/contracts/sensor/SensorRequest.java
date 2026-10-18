package com.smartenergy.contracts.sensor;

import com.smartenergy.contracts.common.SensorIdPattern;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * Corps de POST /api/sensors et PUT /api/sensors/{id}.
 * {@code version} (optionnel) : verrou optimiste, un PUT avec une version perimee renvoie 409 VERSION_CONFLICT.
 * {@code active} absent = true a la creation.
 */
public record SensorRequest(
        @NotBlank(message = "ne doit pas etre vide")
        @Pattern(regexp = SensorIdPattern.REGEX, message = SensorIdPattern.MESSAGE)
        String sensorId,

        @NotBlank(message = "ne doit pas etre vide")
        String name,

        String building,

        Integer floor,

        @NotNull(message = "est obligatoire")
        @DecimalMin(value = "0.0", inclusive = false, message = "doit etre strictement superieur a 0")
        Double maxPowerKw,

        Boolean active,

        List<String> tags,

        Long version) {}
