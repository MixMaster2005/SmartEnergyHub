package com.smartenergy.collector.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Tests unitaires du compteur {@code energy_measurements_received_total{result}} (CDC section 9). */
class MeasurementMetricsFilterTest {

    private static final String CHEMIN = "/api/measurements";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MeasurementMetricsFilter filter = new MeasurementMetricsFilter(registry);

    @BeforeEach
    void vider() {
        registry.clear();
    }

    private static FilterChain statut(int code) {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(code);
    }

    private double compteur(String result) {
        Counter counter = registry.find(MeasurementMetricsFilter.RECEIVED_COUNTER)
                .tag(MeasurementMetricsFilter.RESULT_TAG, result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void executer(String methode, String chemin, FilterChain chain)
            throws Exception {
        filter.doFilter(new MockHttpServletRequest(methode, chemin),
                new MockHttpServletResponse(), chain);
    }

    @Test
    void mesureAcceptee_202_compteAccepted() throws Exception {
        executer("POST", CHEMIN, statut(202));

        assertThat(compteur(MeasurementMetricsFilter.ACCEPTED)).isEqualTo(1.0);
        assertThat(compteur(MeasurementMetricsFilter.REJECTED)).isZero();
    }

    @Test
    void validationRefusee_400_compteRejected() throws Exception {
        executer("POST", CHEMIN, statut(400));

        assertThat(compteur(MeasurementMetricsFilter.ACCEPTED)).isZero();
        assertThat(compteur(MeasurementMetricsFilter.REJECTED)).isEqualTo(1.0);
    }

    @Test
    void corpsTropGros_413_compteRejected() throws Exception {
        executer("POST", CHEMIN, statut(413));

        assertThat(compteur(MeasurementMetricsFilter.REJECTED)).isEqualTo(1.0);
    }

    @Test
    void brokerIndisponible_503_compteRejected() throws Exception {
        executer("POST", CHEMIN, statut(503));

        assertThat(compteur(MeasurementMetricsFilter.REJECTED)).isEqualTo(1.0);
    }

    @Test
    void exceptionNonGeree_compteRejected() {
        FilterChain chain = (request, response) -> {
            throw new ServletException("panne simulee");
        };

        assertThatThrownBy(() -> executer("POST", CHEMIN, chain))
                .isInstanceOf(ServletException.class);
        assertThat(compteur(MeasurementMetricsFilter.REJECTED)).isEqualTo(1.0);
    }

    @Test
    void autreMethodeOuAutreChemin_ignore() throws Exception {
        executer("GET", CHEMIN, statut(200));
        executer("POST", "/api/analytics/autre", statut(202));

        assertThat(registry.find(MeasurementMetricsFilter.RECEIVED_COUNTER).counters()).isEmpty();
    }

    @Test
    void deuxAppels_cumule() throws Exception {
        executer("POST", CHEMIN, statut(202));
        executer("POST", CHEMIN, statut(202));

        assertThat(compteur(MeasurementMetricsFilter.ACCEPTED)).isEqualTo(2.0);
    }
}
