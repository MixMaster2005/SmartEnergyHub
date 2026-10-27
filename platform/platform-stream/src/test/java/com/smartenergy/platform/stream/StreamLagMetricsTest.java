package com.smartenergy.platform.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Tests unitaires (sans Spring ni Redis reel) des metriques de sante du stream (etape 11) :
 * noms de metriques et etiquette "group", rafraichissement pending/lag, tolerance d'une
 * indisponibilite Redis (D-24).
 */
class StreamLagMetricsTest {

    private static final String NOM_STREAM = "energy-measurements";
    private static final String GROUPE = "storage-group";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final StreamLagMetrics metrics =
            new StreamLagMetrics(
                    registry,
                    redis,
                    new StreamProperties(
                            NOM_STREAM,
                            "energy-measurements-dlq",
                            500000L,
                            GROUPE,
                            "",
                            50,
                            2000L,
                            Duration.ofSeconds(60),
                            Duration.ofSeconds(30),
                            5));

    @BeforeEach
    void brancher_redis() {
        when(redis.opsForStream()).thenReturn(streamOps);
    }

    /** Reponse XINFO GROUPS : liste de listes "cle, valeur, cle, valeur..." (protocole Redis). */
    private static StreamInfo.XInfoGroups groupes(long pending, Long lag) {
        List<Object> champs = new ArrayList<>(List.of(
                "name", GROUPE,
                "consumers", 1L,
                "pending", pending,
                "last-delivered-id", "1700000000000-0"));
        if (lag != null) {
            champs.add("lag");
            champs.add(lag);
        }
        return StreamInfo.XInfoGroups.fromList(List.of(champs));
    }

    private static PendingMessagesSummary resume(long pending) {
        return new PendingMessagesSummary(GROUPE, pending, Range.unbounded(), Map.of());
    }

    @Test
    void les_jauges_sont_enregistrees_avec_le_nom_et_le_tag_group() {
        assertThat(registry.find("energy_stream_pending").tag("group", GROUPE).gauge()).isNotNull();
        assertThat(registry.find("energy_stream_lag").tag("group", GROUPE).gauge()).isNotNull();
    }

    @Test
    void refresh_met_a_jour_pending_et_lag() {
        when(streamOps.pending(NOM_STREAM, GROUPE)).thenReturn(resume(7L));
        when(streamOps.groups(NOM_STREAM)).thenReturn(groupes(7L, 5L));

        metrics.refresh();

        assertThat(registry.find("energy_stream_pending").tag("group", GROUPE).gauge().value())
                .isEqualTo(7.0);
        assertThat(registry.find("energy_stream_lag").tag("group", GROUPE).gauge().value())
                .isEqualTo(5.0);
    }

    @Test
    void redis_indisponible_conserve_les_dernieres_valeurs_sans_lever() {
        when(streamOps.pending(NOM_STREAM, GROUPE)).thenReturn(resume(7L));
        when(streamOps.groups(NOM_STREAM)).thenReturn(groupes(7L, 5L));
        metrics.refresh();

        when(streamOps.pending(NOM_STREAM, GROUPE)).thenThrow(new RedisConnectionFailureException("boom"));
        when(streamOps.groups(NOM_STREAM)).thenThrow(new RedisConnectionFailureException("boom"));

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(registry.find("energy_stream_pending").tag("group", GROUPE).gauge().value())
                .isEqualTo(7.0);
        assertThat(registry.find("energy_stream_lag").tag("group", GROUPE).gauge().value())
                .isEqualTo(5.0);
    }

    @Test
    void lag_inconnu_ou_groupe_absent_vaut_zero() {
        when(streamOps.pending(NOM_STREAM, GROUPE)).thenReturn(resume(0L));
        // Redis < 7 : le champ "lag" est absent de XINFO GROUPS
        when(streamOps.groups(NOM_STREAM)).thenReturn(groupes(0L, null));

        metrics.refresh();

        assertThat(registry.find("energy_stream_lag").tag("group", GROUPE).gauge().value()).isZero();

        // Groupe absent de la reponse (stream jamais lu) : 0 sans erreur
        when(streamOps.groups(NOM_STREAM)).thenReturn(StreamInfo.XInfoGroups.fromList(List.of()));
        assertThatCode(metrics::refresh).doesNotThrowAnyException();
        assertThat(registry.find("energy_stream_lag").tag("group", GROUPE).gauge().value()).isZero();
    }
}
