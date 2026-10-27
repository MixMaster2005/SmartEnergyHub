package com.smartenergy.collector.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Tests unitaires des regles temporelles (sans Spring) : bornes exactes, futur, nominal. */
class MeasurementTimeValidatorTest {

    private static final Instant NOW = Instant.parse("2026-09-30T08:15:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MeasurementTimeValidator validator =
            new MeasurementTimeValidator(new MeasurementProperties(MAX_AGE, MAX_FUTURE, 10240), clock);

    @Test
    void valeur_nominale_est_acceptee() {
        assertThatCode(() -> validator.check(NOW)).doesNotThrowAnyException();
        assertThatCode(() -> validator.check(NOW.minus(Duration.ofHours(3))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.check(NOW.plus(Duration.ofMinutes(4))))
                .doesNotThrowAnyException();
    }

    @Test
    void borne_exacte_ancien_est_acceptee() {
        assertThatCode(() -> validator.check(NOW.minus(MAX_AGE))).doesNotThrowAnyException();
    }

    @Test
    void une_millisecond_apres_la_borne_ancienne_est_rejetee() {
        assertThatThrownBy(() -> validator.check(NOW.minus(MAX_AGE).minusMillis(1)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_TOO_OLD);
                            assertThat(e.code().status()).isEqualTo(400);
                            assertThat(e.getMessage()).contains("trop ancien").contains("24 h");
                        });
    }

    @Test
    void mesure_de_25_h_est_rejetee() {
        assertThatThrownBy(() -> validator.check(NOW.minus(Duration.ofHours(25))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_TOO_OLD));
    }

    @Test
    void borne_exacte_futur_est_acceptee() {
        assertThatCode(() -> validator.check(NOW.plus(MAX_FUTURE))).doesNotThrowAnyException();
    }

    @Test
    void une_millisecond_apres_la_borne_futur_est_rejetee() {
        assertThatThrownBy(() -> validator.check(NOW.plus(MAX_FUTURE).plusMillis(1)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_IN_FUTURE);
                            assertThat(e.code().status()).isEqualTo(400);
                            assertThat(e.getMessage()).contains("futur").contains("5 min");
                        });
    }

    @Test
    void mesure_de_6_min_dans_le_futur_est_rejetee() {
        assertThatThrownBy(() -> validator.check(NOW.plus(Duration.ofMinutes(6))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_IN_FUTURE));
    }

    @Test
    void proprietes_personnalisees_sont_prises_en_compte() {
        MeasurementTimeValidator strict =
                new MeasurementTimeValidator(
                        new MeasurementProperties(Duration.ofHours(2), MAX_FUTURE, 10240), clock);

        assertThatCode(() -> strict.check(NOW.minus(Duration.ofHours(2))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> strict.check(NOW.minus(Duration.ofHours(3))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.MEASUREMENT_TOO_OLD);
                            assertThat(e.getMessage()).contains("2 h");
                        });
        // Le validateur par defaut (24 h) accepte toujours une mesure de 3 h.
        assertThatCode(() -> validator.check(NOW.minus(Duration.ofHours(3))))
                .doesNotThrowAnyException();
    }
}
