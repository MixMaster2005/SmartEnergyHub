package com.smartenergy.analytics.web;

// ========================================================================
// TODO(etape-8) - Routes /api/analytics/** et alias /api/sensors/{id}/daily-summary
// ========================================================================
// Guide : etapes 6, 8 et 9. CDC : section 5.4, decision D-17.
//
//  - GET /api/analytics/{sensorId}/summary?from&to
//  - GET /api/analytics/{sensorId}/daily-summary?date=YYYY-MM-DD
//  - GET /api/sensors/{id}/daily-summary?date=...   <- ALIAS du guide : meme methode, deux @GetMapping.
//    (sensor-service ne connait pas ce chemin ; la gateway en extension route "/api/sensors/*/daily-summary"
//    vers analytics AVANT la regle generale "/api/sensors/**".)
//  - GET /api/analytics/{sensorId}/anomalies?from&to&type&page&size
//  - GET /api/analytics/exceedances?threshold&from&to      (threshold obligatoire)
//  - GET /api/analytics/{sensorId}/forecast?horizonHours   (extension : 501 FORECAST_DISABLED si desactive)
//
// Declarer "/api/analytics/exceedances" sans ambiguite avec "/api/analytics/{sensorId}/..." (segments differents : OK).
// Aucune logique ici : deleguer a AnalyticsQueryService / ForecastService. Documenter en OpenAPI avec exemples.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.analytics.service.AnalyticsQueryService;
// import com.smartenergy.contracts.analytics.*;
// import com.smartenergy.contracts.common.ApiPaths;
// import java.time.Instant;
// import java.time.LocalDate;
// import org.springframework.web.bind.annotation.*;
//
// @RestController
// public class AnalyticsController {
//     private final AnalyticsQueryService service;
//
//     public AnalyticsController(AnalyticsQueryService service) {
//         this.service = service;
//     }
//
//     @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/summary")
//     public SummaryResponse summary(@PathVariable String sensorId,
//             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
//         return service.summary(sensorId, from, to);
//     }
//
//     @GetMapping({ApiPaths.ANALYTICS + "/{sensorId}/daily-summary", ApiPaths.SENSORS + "/{sensorId}/daily-summary"})
//     public DailySummaryResponse dailySummary(@PathVariable String sensorId,
//             @RequestParam(required = false) LocalDate date) {
//         return service.dailySummary(sensorId, date);
//     }
//
//     // anomalies(...), exceedances(...), forecast(...)
// }
