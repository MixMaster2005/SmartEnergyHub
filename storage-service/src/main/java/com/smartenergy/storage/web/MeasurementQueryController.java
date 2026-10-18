package com.smartenergy.storage.web;

// ========================================================================
// TODO(etape-8) - Routes GET /api/measurements/{sensorId} et /latest
// ========================================================================
// Guide : etape 8. CDC : section 5.4. Documenter avec OpenAPI (exemples de requetes et de reponses).
//
//  - GET /api/measurements/{sensorId}?from&to&limit&cursor&order -> 200 MeasurementHistoryResponse
//  - GET /api/measurements/{sensorId}/latest -> 200 MeasurementItem | 404 MEASUREMENT_NOT_FOUND
//  Parametres from/to : Instant (ISO-8601) via @RequestParam + @DateTimeFormat(iso = ISO.DATE_TIME) ;
//  type invalide -> 400 INVALID_PARAMETER automatiquement (platform-web).
//  Les controles metier (plage max, limit) sont dans MeasurementQueryService, pas ici.
//
// Ne pas confondre avec POST /api/measurements du collector : meme prefixe, autre service (la gateway
// route par methode HTTP en extension).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.common.ApiPaths;
// import com.smartenergy.contracts.measurement.MeasurementHistoryResponse;
// import com.smartenergy.contracts.measurement.MeasurementItem;
// import com.smartenergy.storage.service.MeasurementQueryService;
// import java.time.Instant;
// import org.springframework.web.bind.annotation.*;
//
// @RestController
// @RequestMapping(ApiPaths.MEASUREMENTS)
// public class MeasurementQueryController {
//     private final MeasurementQueryService service;
//
//     public MeasurementQueryController(MeasurementQueryService service) {
//         this.service = service;
//     }
//
//     @GetMapping("/{sensorId}")
//     public MeasurementHistoryResponse history(@PathVariable String sensorId,
//             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
//             @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
//             @RequestParam(defaultValue = "desc") String order) {
//         return service.history(sensorId, from, to, limit, cursor, order);
//     }
//
//     @GetMapping("/{sensorId}/latest")
//     public MeasurementItem latest(@PathVariable String sensorId) {
//         return service.latest(sensorId);
//     }
// }
