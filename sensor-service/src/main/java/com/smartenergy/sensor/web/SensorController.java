package com.smartenergy.sensor.web;

import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.sensor.SensorRequest;
import com.smartenergy.contracts.sensor.SensorResponse;
import com.smartenergy.sensor.service.SensorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contrat : CDC section 5.4 (sensor-service). */
@RestController
@RequestMapping(ApiPaths.SENSORS)
@Tag(name = "Capteurs", description = "Registre des capteurs et de leurs seuils")
public class SensorController {

    private final SensorService service;

    public SensorController(SensorService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Creer un capteur", description = "201 + Location ; 409 SENSOR_ALREADY_EXISTS si l'identifiant existe.")
    public ResponseEntity<SensorResponse> create(@Valid @RequestBody SensorRequest request) {
        SensorResponse created = service.create(request);
        return ResponseEntity.created(URI.create(ApiPaths.SENSORS + "/" + created.sensorId())).body(created);
    }

    @GetMapping
    @Operation(summary = "Lister les capteurs", description = "Pagination page/size (size max 100), filtres building et active.")
    public PageResponse<SensorResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) Boolean active) {
        return service.list(page, size, building, active);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lire un capteur", description = "404 SENSOR_NOT_FOUND si inconnu.")
    public SensorResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Remplacer un capteur",
            description = "Le sensorId du corps doit egaler celui de l'URL. 'version' optionnelle : 409 VERSION_CONFLICT si perimee.")
    public SensorResponse update(@PathVariable String id, @Valid @RequestBody SensorRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactiver un capteur (suppression logique)")
    public ResponseEntity<Void> deactivate(@PathVariable String id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
