package com.smartenergy.platform.stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Metriques de sante du stream pour le groupe consommateur du service (CDC section 9,
 * decisions D-02, D-06, D-24) : jusqu'ou le groupe est-il en retard sur le flux ?
 *
 * <p>Deux jauges Micrometer etiquettees {@code group=<nom du groupe>} :
 *
 * <ul>
 *   <li>{@code energy_stream_pending} : messages livres au groupe mais non encore acquittes
 *       (resume {@code XPENDING}). En regime normal proche de 0 ; un service arrete pendant
 *       l'envoi le voit monter (preuve des scenarios 5 et 6).</li>
 *   <li>{@code energy_stream_lag} : messages restant a lire par le groupe (champ {@code lag}
 *       de {@code XINFO GROUPS}). Indique si le consommateur suit le debit d'entree.</li>
 * </ul>
 *
 * <p>Rafraichies par une tache planifiee toutes les {@value #REFRESH_MS} ms et jamais dans le
 * callback du Gauge : chaque scrape Prometheus declencherait sinon un aller-retour Redis
 * (decision D-24). Redis indisponible : la derniere valeur connue est conservee et l'erreur
 * est journalisee en warn — une metrique ne doit jamais faire tomber un service.
 *
 * <p>Pieges : le champ {@code lag} n'existe que sur Redis 7+ ; Spring Data Redis 3.5 ne
 * l'expose pas en acces type (lecture via {@link StreamInfo.XInfoGroup#getRaw()}).
 *
 * <p>Enregistrement : {@link PlatformStreamAutoConfiguration}, uniquement pour un service qui
 * definit {@code smartenergy.stream.group} (storage, analytics ; jamais le collector qui ne
 * consomme pas). Verification : {@code GET :808x/actuator/metrics/energy_stream_pending}.
 */
public class StreamLagMetrics {

    private static final Logger log = LoggerFactory.getLogger(StreamLagMetrics.class);

    /** Delai entre deux rafraichissements (decision D-24 : pas d'appel Redis dans le callback du Gauge). */
    static final long REFRESH_MS = 5_000L;

    private final StringRedisTemplate redis;
    private final StreamProperties props;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lag = new AtomicLong();

    public StreamLagMetrics(MeterRegistry registry, StringRedisTemplate redis, StreamProperties props) {
        this.redis = redis;
        this.props = props;
        registry.gauge("energy_stream_pending", Tags.of("group", props.group()), pending);
        registry.gauge("energy_stream_lag", Tags.of("group", props.group()), lag);
    }

    /**
     * XPENDING (resume) puis XINFO GROUPS (champ "lag") -> valeurs des jauges. Les deux lectures
     * sont separees : une erreur sur l'une n'empeche pas la mise a jour de l'autre.
     */
    @Scheduled(fixedDelay = REFRESH_MS)
    void refresh() {
        StreamOperations<String, String, String> stream = redis.opsForStream();
        try {
            PendingMessagesSummary summary = stream.pending(props.name(), props.group());
            pending.set(summary == null ? 0L : summary.getTotalPendingMessages());
        } catch (RuntimeException e) {
            log.warn("metrique energy_stream_pending non rafraichie (groupe {}) : {}",
                    props.group(), e.getMessage());
        }
        try {
            lag.set(readLag(stream));
        } catch (RuntimeException e) {
            log.warn("metrique energy_stream_lag non rafraichie (groupe {}) : {}",
                    props.group(), e.getMessage());
        }
    }

    /**
     * Lag du groupe courant dans la reponse XINFO GROUPS. Groupe absent (Redis encore vide) ou
     * champ "lag" absent/null (Redis &lt; 7) : 0.
     */
    private long readLag(StreamOperations<String, String, String> stream) {
        StreamInfo.XInfoGroups groups = stream.groups(props.name());
        if (groups == null) {
            return 0L;
        }
        for (StreamInfo.XInfoGroup group : groups) {
            if (props.group().equals(group.groupName())) {
                Object raw = group.getRaw().get("lag");
                return raw instanceof Number number ? number.longValue() : 0L;
            }
        }
        return 0L;
    }
}
