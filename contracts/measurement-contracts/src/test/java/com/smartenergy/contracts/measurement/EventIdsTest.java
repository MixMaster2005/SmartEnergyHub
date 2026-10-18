package com.smartenergy.contracts.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventIdsTest {

    @Test
    void uuidV5_respecte_le_vecteur_de_reference_RFC() {
        // Vecteur classique : uuid5(NAMESPACE_DNS, "python.org")
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertThat(EventIds.uuidV5(dns, "python.org").toString())
                .isEqualTo("886313e1-3b8a-5372-9b90-0c9aee199e5d");
    }

    @Test
    void meme_mesure_donne_meme_eventId() {
        Instant ts = Instant.parse("2026-09-30T08:15:00Z");
        UUID a = EventIds.forMeasurement("SENSOR-001", ts);
        UUID b = EventIds.forMeasurement("SENSOR-001", ts);
        assertThat(a).isEqualTo(b);
        assertThat(a.version()).isEqualTo(5);
    }

    @Test
    void capteur_ou_instant_different_donne_eventId_different() {
        Instant ts = Instant.parse("2026-09-30T08:15:00Z");
        assertThat(EventIds.forMeasurement("SENSOR-001", ts))
                .isNotEqualTo(EventIds.forMeasurement("SENSOR-002", ts))
                .isNotEqualTo(EventIds.forMeasurement("SENSOR-001", ts.plusMillis(1)));
    }

    @Test
    void precision_sous_la_milliseconde_est_ignoree() {
        Instant a = Instant.parse("2026-09-30T08:15:00.123Z");
        Instant b = Instant.parse("2026-09-30T08:15:00.123456Z");
        assertThat(EventIds.forMeasurement("SENSOR-001", a)).isEqualTo(EventIds.forMeasurement("SENSOR-001", b));
    }
}
