package com.smartenergy.platform.stream;

// ========================================================================
// TODO(etape-11) - Metriques Micrometer du stream (pending, lag)
// ========================================================================
// Guide : etape 11 (observabilite). CDC : section 9. Noms de metriques : energy_stream_pending, energy_stream_lag.
//
// Objectif : rendre visible le retard des consommateurs (preuve des scenarios 5 et 6, alerte a 50 % de MAXLEN).
//
// A faire :
//  1. Enregistrer deux Gauge par groupe, etiquette group=<nom> :
//     - energy_stream_pending : nombre de messages livres mais non acquittes (XPENDING <stream> <group>).
//     - energy_stream_lag : messages restant a lire par le groupe (champ "lag" de XINFO GROUPS <stream>).
//  2. Rafraichir via une tache planifiee (toutes les 5 a 10 s) plutot que dans le callback du Gauge,
//     pour ne pas faire un appel Redis a chaque scrape Prometheus.
//  3. Verifier http://localhost:808x/actuator/metrics/energy_stream_pending.
//
// Piege : le champ "lag" de XINFO GROUPS n'existe que sur Redis recent (7.x : OK avec l'image redis:7).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import io.micrometer.core.instrument.MeterRegistry;
// import java.util.concurrent.atomic.AtomicLong;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.scheduling.annotation.Scheduled;
//
// public class StreamLagMetrics {
//     private final AtomicLong pending = new AtomicLong();
//     private final AtomicLong lag = new AtomicLong();
//
//     public StreamLagMetrics(MeterRegistry registry, StringRedisTemplate redis, StreamProperties props) {
//         // registry.gauge("energy_stream_pending", Tags.of("group", props.group()), pending);
//         // registry.gauge("energy_stream_lag", Tags.of("group", props.group()), lag);
//     }
//
//     @Scheduled(fixedDelay = 5000)
//     void refresh() { /* XPENDING / XINFO GROUPS -> pending.set(...), lag.set(...) */ }
// }
