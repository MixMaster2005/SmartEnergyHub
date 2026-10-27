package com.smartenergy.storage.web;

import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
import com.smartenergy.contracts.measurement.MeasurementItem;
import com.smartenergy.storage.service.MeasurementQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes de lecture de l'historique (CDC section 5.4, EF-06 et EF-07).
 *
 * <p>Aucune logique ici : la conversion des parametres incomplets et les controles metier
 * (plage maximale de 7 jours, bornes de limit, ordre de tri) vivent dans
 * {@link MeasurementQueryService}. Le parseur {@code @DateTimeFormat(ISO.DATE_TIME)} reconnait
 * l'ISO-8601 ("2026-09-30T08:15:00Z") ; une valeur illisible leve une
 * {@code MethodArgumentTypeMismatchException} convertie en 400 {@code INVALID_PARAMETER} par le
 * {@code GlobalExceptionHandler} de platform-web.
 *
 * <p><b>Attention collision de prefixe</b> : le collector expose {@code POST /api/measurements}
 * (ecriture) alors que storage-service expose {@code GET /api/measurements/{sensorId}} (lecture) :
 * meme prefixe, deux services. La gateway (extension) route par methode HTTP ; en local, chaque
 * port est distinct (8081 vs 8082).
 *
 * <p>Toutes les routes sont documentees en OpenAPI avec exemples (EF-17) : Swagger UI sur
 * http://localhost:8082/swagger-ui.html.
 */
@RestController
@RequestMapping(ApiPaths.MEASUREMENTS)
@Tag(name = "Historique des mesures", description = "Consultation de l'historique et de la derniere mesure (CDC 5.4)")
public class MeasurementQueryController {

    private final MeasurementQueryService service;

    public MeasurementQueryController(MeasurementQueryService service) {
        this.service = service;
    }

    @GetMapping("/{sensorId}")
    @Operation(summary = "Historique paginé d'un capteur",
            description = "Plage [from, to) (defaut : dernières 24 h), limit 100 par defaut (max 1000), "
                    + "order desc par defaut (asc possible). Pagination par nextCursor opaque (rejoue la "
                    + "requete avec &cursor=...). 400 INVALID_PARAMETER (limit, order, to <= from, curseur "
                    + "invalide) ou RANGE_TOO_LARGE (plage > 7 jours). Capteur sans donnee : 200, items vide.")
    public MeasurementHistoryResponse history(
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "desc") String order) {
        return service.history(sensorId, from, to, limit, cursor, order);
    }

    @GetMapping("/{sensorId}/latest")
    @Operation(summary = "Derniere mesure d'un capteur",
            description = "Lecture O(1) de la derniere mesure (cache-aside Redis 30 s). "
                    + "404 MEASUREMENT_NOT_FOUND si le capteur n'a jamais publie de mesure.")
    public MeasurementItem latest(@PathVariable String sensorId) {
        return service.latest(sensorId);
    }
}
