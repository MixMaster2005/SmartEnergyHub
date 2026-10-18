package com.smartenergy.collector.stream;

// ========================================================================
// TODO(etape-3) - Publication des mesures dans Redis Streams (XADD)
// ========================================================================
// Guide : etapes 3 et 4. CDC : section 5.3 (contrat d'evenement), decisions D-03, D-06, D-07.
//
// Objectif : ecrire UNE entree dans le stream energy-measurements par mesure valide.
//
// A faire :
//  1. Convertir l'evenement en champs texte : MeasurementEventCodec.toFields(measurement)
//     (ne PAS reecrire la serialisation : le codec est le contrat partage).
//  2. XADD sur props.name() avec MAXLEN ~ props.maxLen() (trim approximatif, decision D-06).
//     Avec Spring Data Redis : redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(...),
//     <options MAXLEN approximatif>) - verifier l'API de votre version (XAddOptions) ; sinon commande brute.
//  3. Retourner l'id du message Redis (ex. 1700000000000-0) pour le journal.
//  4. Redis indisponible (RedisConnectionFailureException, QueryTimeoutException...) :
//     lever ApiException.unavailable("...", 5)  ->  HTTP 503 + Retry-After. JAMAIS de 202 sans XADD reussi.
//  5. Compter les publications : compteur Micrometer energy_measurements_published_total
//     et chronometrer la publication (voir CDC section 9).
//
// Tests : mesure valide -> XLEN +1 et champs conformes a contracts/schemas/measurement-event.schema.json ;
// Redis arrete -> 503 ; meme mesure envoyee deux fois -> 2 entrees (le doublon est neutralise en aval).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import com.smartenergy.platform.stream.StreamProperties;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component;
//
// @Component
// public class MeasurementPublisher {
//     private final StringRedisTemplate redis;
//     private final StreamProperties props;
//
//     public MeasurementPublisher(StringRedisTemplate redis, StreamProperties props) {
//         this.redis = redis;
//         this.props = props;
//     }
//
//     /** @return l'id du message Redis */
//     public String publish(EnergyMeasurement measurement) {
//         // Map<String, String> fields = MeasurementEventCodec.toFields(measurement);
//         // try { XADD ... } catch (RuntimeException e) { throw ApiException.unavailable("Redis indisponible", 5); }
//         throw new UnsupportedOperationException("TODO etape 3");
//     }
// }
