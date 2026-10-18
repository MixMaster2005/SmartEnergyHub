package com.smartenergy.collector.validation;

// ========================================================================
// TODO(etape-1) - Regles temporelles : mesure trop ancienne ou dans le futur
// ========================================================================
// Guide : etape 1 (questions "mesure tres ancienne ?", "pourquoi UTC ?"). CDC : section 5.2, decision sur 24 h / 5 min.
// Les regles STATIQUES (sensorId, plages) sont dans EnergyMeasurementRequest. Les regles qui dependent de
// l'HORLOGE sont ici, parametrees par MeasurementProperties (maxAge, maxFuture).
//
// A faire :
//  - check(Instant timestamp) :
//      * timestamp apres (now + maxFuture) -> ApiException(MEASUREMENT_IN_FUTURE, ...)   (HTTP 400)
//      * timestamp avant (now - maxAge)   -> ApiException(MEASUREMENT_TOO_OLD, ...)      (HTTP 400)
//  - Injecter une java.time.Clock pour tester sans dependre de l'heure reelle (bean Clock.systemUTC()).
//  - Messages explicites, par exemple "timestamp trop ancien : limite 24 h".
//
// Test unitaire a ecrire (sans Spring) : limites exactes (now - maxAge pile, +1 ms), futur, valeur nominale.
// Rapport : justifier 24 h (reprise apres coupure reseau d'un capteur) et 5 min (derive d'horloge).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.collector.config.MeasurementProperties;
// import com.smartenergy.contracts.error.ApiException;
// import com.smartenergy.contracts.error.ErrorCode;
// import java.time.Clock;
// import java.time.Instant;
// import org.springframework.stereotype.Component;
//
// @Component
// public class MeasurementTimeValidator {
//     private final MeasurementProperties props;
//     private final Clock clock;
//
//     public MeasurementTimeValidator(MeasurementProperties props, Clock clock) {
//         this.props = props;
//         this.clock = clock;
//     }
//
//     public void check(Instant timestamp) {
//         // Instant now = clock.instant();
//         // if (timestamp.isAfter(now.plus(props.maxFuture()))) throw ApiException.of(ErrorCode.MEASUREMENT_IN_FUTURE, "...");
//         // if (timestamp.isBefore(now.minus(props.maxAge())))  throw ApiException.of(ErrorCode.MEASUREMENT_TOO_OLD, "...");
//     }
// }
