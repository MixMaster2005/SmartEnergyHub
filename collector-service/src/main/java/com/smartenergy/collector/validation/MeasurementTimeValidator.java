package com.smartenergy.collector.validation;

import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Regles temporelles de validation d'une mesure (CDC section 5.2).
 *
 * <p>Les regles STATIQUES (sensorId, plages de valeurs) sont dans {@code EnergyMeasurementRequest} ;
 * celles qui dependent de l'HORLOGE sont ici, parametrees par {@link MeasurementProperties} :
 *
 * <ul>
 *   <li>timestamp strictement apres {@code now + maxFuture} (5 min par defaut) -&gt;
 *       {@code MEASUREMENT_IN_FUTURE} (HTTP 400) : derive d'horloge de l'emetteur ;
 *   <li>timestamp strictement avant {@code now - maxAge} (24 h par defaut) -&gt;
 *       {@code MEASUREMENT_TOO_OLD} (HTTP 400) : mesure obsolete (reprise apres coupure reseau).
 * </ul>
 *
 * <p>Les bornes exactes sont donc ACCEPTEES ; un depassement d'une milliseconde est rejete.
 * L'horloge est injectee ({@code Clock.systemUTC()}) pour tester sans dependre de l'heure reelle.
 * Un timestamp nul est rejete en amont par {@code @NotNull} sur {@code EnergyMeasurementRequest}.
 */
@Component
public class MeasurementTimeValidator {
    private final MeasurementProperties props;
    private final Clock clock;

    public MeasurementTimeValidator(MeasurementProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * Verifie le timestamp de la mesure contre les fenetres {@code maxAge} / {@code maxFuture}.
     *
     * @throws ApiException {@code MEASUREMENT_IN_FUTURE} ou {@code MEASUREMENT_TOO_OLD} (HTTP 400)
     */
    public void check(Instant timestamp) {
        Instant now = clock.instant();
        if (timestamp.isAfter(now.plus(props.maxFuture()))) {
            throw ApiException.of(
                    ErrorCode.MEASUREMENT_IN_FUTURE,
                    "timestamp dans le futur : tolerance " + formater(props.maxFuture()));
        }
        if (timestamp.isBefore(now.minus(props.maxAge()))) {
            throw ApiException.of(
                    ErrorCode.MEASUREMENT_TOO_OLD,
                    "timestamp trop ancien : limite " + formater(props.maxAge()));
        }
    }

    /** Formate une duree en francais : PT24H -&gt; "24 h", PT5M -&gt; "5 min". */
    private static String formater(Duration d) {
        if (d.toMinutes() % 60 == 0) {
            return d.toHours() + " h";
        }
        return d.toMinutes() + " min";
    }
}
