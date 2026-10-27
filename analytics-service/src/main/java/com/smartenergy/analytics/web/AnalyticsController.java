package com.smartenergy.analytics.web;

import com.smartenergy.analytics.service.AnalyticsQueryService;
import com.smartenergy.contracts.analytics.AnomalyResponse;
import com.smartenergy.contracts.analytics.AnomalyType;
import com.smartenergy.contracts.analytics.DailySummaryResponse;
import com.smartenergy.contracts.analytics.ExceedanceResponse;
import com.smartenergy.contracts.analytics.ForecastResponse;
import com.smartenergy.contracts.analytics.SummaryResponse;
import com.smartenergy.contracts.common.ApiPaths;
import com.smartenergy.contracts.common.PageResponse;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes de consultation des indicateurs (CDC section 5.4, decisions D-13 et D-17).
 *
 * <p>Zero logique metier ici : chaque route delegue a {@link AnalyticsQueryService}. Les
 * parametres de date sont de type {@code Instant}/{@code LocalDate} avec
 * {@code @DateTimeFormat} : une valeur illisible (ex. {@code ?from=hier}) produit un 400
 * {@code INVALID_PARAMETER} via le {@code GlobalExceptionHandler} de platform-web.
 *
 * <p><b>Alias daily-summary (decision D-17)</b> : la meme methode est exposee sous deux chemins,
 * {@code /api/analytics/{sensorId}/daily-summary} et {@code /api/sensors/{sensorId}/daily-summary}.
 * sensor-service ne sert QUE {@code /api/sensors/**} CRUD ; la gateway (extension) route le
 * chemin daily-summary sous /api/sensors vers analytics AVANT la regle generale
 * {@code /api/sensors/**} (regle plus specifique d'abord).
 *
 * <p><b>Exceedances sans ambiguïté</b> : {@code /api/analytics/exceedances} ne peut pas etre
 * confondu avec {@code /api/analytics/{sensorId}/...} (un segment de moins) ; Spring tranche sur
 * le nombre de segments.
 *
 * <p>Toutes les routes sont documentees en OpenAPI avec exemples (EF-17) : Swagger UI sur
 * http://localhost:8083/swagger-ui.html.
 */
@RestController
@Tag(name = "Analytics", description = "Indicateurs, anomalies, exces de seuil et prevision (CDC 5.4)")
public class AnalyticsController {

    private final AnalyticsQueryService service;

    public AnalyticsController(AnalyticsQueryService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/summary")
    @Operation(summary = "Indicateurs sur une plage",
            description = "Granularite horaire (bornes alignees sur les heures pleines, D-13), plage max 31 jours. "
                    + "Defauts : to = maintenant, from = to - 24 h. 400 INVALID_PARAMETER / RANGE_TOO_LARGE ; "
                    + "404 NO_DATA si aucun agregat horaire sur la periode.")
    public SummaryResponse summary(
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.summary(sensorId, from, to);
    }

    @GetMapping({ApiPaths.ANALYTICS + "/{sensorId}/daily-summary",
            ApiPaths.SENSORS + "/{sensorId}/daily-summary"})
    @Operation(summary = "Agregat quotidien (alias D-17)",
            description = "Deux chemins identiques : /api/analytics/{id}/daily-summary et "
                    + "/api/sensors/{id}/daily-summary (alias route vers analytics par la gateway, D-17). "
                    + "date au format YYYY-MM-DD, defaut = jour UTC courant. 404 NO_DATA si absent.")
    public DailySummaryResponse dailySummary(
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailySummary(sensorId, date);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/anomalies")
    @Operation(summary = "Anomalies d'un capteur",
            description = "Liste paginee, plus recentes d'abord (page indexee a 0, size defaut 20, max 100). "
                    + "type optionnel : THRESHOLD, ZSCORE ou FORECAST_RESIDUAL. from/to optionnels. "
                    + "400 INVALID_PARAMETER si pagination ou plage invalide.")
    public PageResponse<AnomalyResponse> anomalies(
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AnomalyType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.anomalies(sensorId, from, to, type, page, size);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/exceedances")
    @Operation(summary = "Capteurs au dela d'un seuil",
            description = "threshold (kW) obligatoire. Sans from/to : etat courant (derniere puissance "
                    + "connue). Avec from/to : maximum des agregats quotidiens de la periode (31 jours max). "
                    + "400 INVALID_PARAMETER si plage invalide.")
    public List<ExceedanceResponse> exceedances(
            @RequestParam double threshold,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.exceedances(threshold, from, to);
    }

    @GetMapping(ApiPaths.ANALYTICS + "/{sensorId}/forecast")
    @Operation(summary = "Prevision (extension 9C)",
            description = "Extension hors MVP : renvoie 501 FORECAST_DISABLED tant que "
                    + "smartenergy.analytics.forecast.enabled est a false (defaut) et que le modele "
                    + "n'est pas charge. L'endpoint est deja declare pour figer le contrat (CDC 5.4).")
    public ForecastResponse forecast(
            @PathVariable String sensorId,
            @RequestParam(defaultValue = "6") int horizonHours) {
        throw new ApiException(ErrorCode.FORECAST_DISABLED,
                "Module de prevision desactive (extension etape 9C) : capteur " + sensorId
                        + ", horizon " + horizonHours + " h");
    }
}
