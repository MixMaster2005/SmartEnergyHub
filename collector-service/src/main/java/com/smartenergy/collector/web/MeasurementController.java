package com.smartenergy.collector.web;

// ========================================================================
// TODO(etape-3) - Endpoint POST /api/measurements (202 / 400 / 503)
// ========================================================================
// Guide : etape 3. CDC : section 5.2 (contrat d'ingestion), section 7.2.
// Contrat : 202 sans corps + en-tetes X-Event-Id et X-Request-Id ; 400 Problem Details ; 503 + Retry-After.
//
// A faire :
//  1. @Valid sur @RequestBody EnergyMeasurementRequest : platform-web convertit la violation en 400
//     (code VALIDATION_ERROR, tableau errors[]). Rien a ecrire pour cela.
//  2. timeValidator.check(request.timestamp()).
//  3. EnergyMeasurement.from(request, RequestIdFilter.currentRequestId(), Instant.now()).
//  4. String redisId = publisher.publish(measurement).
//  5. Journaliser sensorId, eventId, redisId (aucune donnee sensible).
//  6. return 202 avec l'en-tete HeaderNames.EVENT_ID.
//  Pourquoi 202 ? La reception et le stockage sont decouples (guide 11.2) : "accepte pour traitement".
//
// Tests (MockMvc ou Testcontainers) : voir CollectorIntegrationTest et docs/tests.md scenarios 1, 2, 9.
// Documenter la route avec les annotations OpenAPI (@Operation, exemples de corps) : critere de l'etape 8.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.collector.stream.MeasurementPublisher;
// import com.smartenergy.collector.validation.MeasurementTimeValidator;
// import com.smartenergy.contracts.common.ApiPaths;
// import com.smartenergy.contracts.common.HeaderNames;
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
// import com.smartenergy.platform.web.RequestIdFilter;
// import jakarta.validation.Valid;
// import java.time.Instant;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RestController;
//
// @RestController
// @RequestMapping(ApiPaths.MEASUREMENTS)
// public class MeasurementController {
//     private final MeasurementPublisher publisher;
//     private final MeasurementTimeValidator timeValidator;
//
//     public MeasurementController(MeasurementPublisher publisher, MeasurementTimeValidator timeValidator) {
//         this.publisher = publisher;
//         this.timeValidator = timeValidator;
//     }
//
//     @PostMapping
//     public ResponseEntity<Void> receive(@Valid @RequestBody EnergyMeasurementRequest request) {
//         // timeValidator.check(request.timestamp());
//         // EnergyMeasurement m = EnergyMeasurement.from(request, RequestIdFilter.currentRequestId(), Instant.now());
//         // String redisId = publisher.publish(m);
//         // return ResponseEntity.accepted().header(HeaderNames.EVENT_ID, m.eventId().toString()).build();
//         return ResponseEntity.status(501).build();
//     }
// }
