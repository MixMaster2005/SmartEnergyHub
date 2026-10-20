package com.smartenergy.collector.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartenergy.collector.stream.MeasurementPublisher;
import com.smartenergy.collector.validation.MeasurementTimeValidator;
import com.smartenergy.contracts.common.HeaderNames;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.measurement.EnergyMeasurementRequest;
import com.smartenergy.contracts.measurement.EventIds;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Tests unitaires (sans Spring ni MockMvc) du controleur d'ingestion des mesures. */
class MeasurementControllerTest {

    private static final String SENSOR_ID = "SENSOR-001";
    private static final Instant TIMESTAMP = Instant.parse("2026-10-20T10:15:30.123Z");

    private final MeasurementPublisher publisher = mock(MeasurementPublisher.class);
    private final MeasurementTimeValidator timeValidator = mock(MeasurementTimeValidator.class);
    private final MeasurementController controller = new MeasurementController(publisher, timeValidator);

    @BeforeEach
    void fixer_trace_id() {
        MDC.put("traceId", "test-trace");
    }

    @AfterEach
    void nettoyer_mdc() {
        MDC.remove("traceId");
    }

    private static EnergyMeasurementRequest requete_valide() {
        return new EnergyMeasurementRequest(SENSOR_ID, TIMESTAMP, 3.72, 226.4, null, null, null, null);
    }

    @Test
    void mesure_valide_renvoie_202_avec_en_tete_event_id() {
        when(publisher.publish(any())).thenReturn("1700000000000-0");

        ResponseEntity<Void> response = controller.receive(requete_valide());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(HeaderNames.EVENT_ID))
                .isEqualTo(EventIds.forMeasurement(SENSOR_ID, TIMESTAMP).toString());
        assertThat(response.getBody()).isNull();
        verify(timeValidator).check(TIMESTAMP);
        verify(publisher).publish(any());
    }

    @Test
    void erreur_temporelle_se_propage() {
        doThrow(ApiException.of(ErrorCode.MEASUREMENT_TOO_OLD, "trop ancien"))
                .when(timeValidator).check(any());

        assertThatThrownBy(() -> controller.receive(requete_valide()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_TOO_OLD));

        verify(publisher, never()).publish(any());
    }

    @Test
    void redis_indisponible_se_propage() {
        doThrow(ApiException.unavailable("Redis indisponible", 5)).when(publisher).publish(any());

        assertThatThrownBy(() -> controller.receive(requete_valide()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.BROKER_UNAVAILABLE);
                            assertThat(e.retryAfterSeconds()).isEqualTo(5);
                        });

        verify(timeValidator).check(TIMESTAMP);
    }

    @Test
    void event_id_est_deterministe() {
        when(publisher.publish(any())).thenReturn("1700000000000-0");

        ResponseEntity<Void> premiere = controller.receive(requete_valide());
        ResponseEntity<Void> seconde = controller.receive(requete_valide());

        assertThat(premiere.getHeaders().getFirst(HeaderNames.EVENT_ID))
                .isEqualTo(seconde.getHeaders().getFirst(HeaderNames.EVENT_ID));
        verify(publisher, times(2)).publish(any());
    }
}
