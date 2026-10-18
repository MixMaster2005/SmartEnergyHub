package com.smartenergy.platform.stream;

// ========================================================================
// TODO(etape-4) - Boucle de consommation Redis Streams (consumer group, ACK, reprise, DLQ)
// ========================================================================
// Guide : etapes 4 et 7. CDC : section 5.3 (regles de consommation), decisions D-02, D-05, D-14.
// Utilisee par StorageStreamConsumer (storage-group) ET AnalyticsStreamConsumer (analytics-group) :
// ecrire cette boucle UNE fois ici evite de la dupliquer.
//
// Objectif : consommer le stream en at-least-once, sans jamais perdre ni bloquer.
//
// A faire, dans l'ordre :
//  1. Au demarrage : creer le groupe de facon IDEMPOTENTE (XGROUP CREATE <stream> <group> 0 MKSTREAM).
//     L'erreur BUSYGROUP signifie "le groupe existe deja" : l'ignorer. Verifier l'API de votre version de
//     Spring Data Redis (createGroup, MKSTREAM) ; sinon passer par redis.execute(...) avec la commande brute.
//  2. Boucle de lecture dans un thread dedie : XREADGROUP GROUP <group> <consumer> COUNT <batchSize>
//     BLOCK <blockMs> STREAMS <stream> >   (">" = seulement les nouveaux messages).
//  3. Pour chaque entree : MeasurementEventCodec.fromFields(...).
//     - MalformedEventException -> envoyer IMMEDIATEMENT en DLQ (voir 6) puis XACK.
//  4. Appeler processBatch(List<EnergyMeasurement>) (implemente par la sous-classe).
//     - Succes -> XACK des ids du lot.
//     - Exception -> NE PAS ACK : le message reste "pending" et sera retente (voir 5).
//  5. Reprise : au demarrage puis toutes les claimInterval, XAUTOCLAIM des messages inactifs depuis plus
//     de claimIdle (consommateur mort ou lot en echec), puis les retraiter.
//     Lire le compteur de livraisons (XPENDING ... detail) : si > maxDeliveries -> DLQ + XACK.
//  6. DLQ : XADD <dlqName> avec les champs d'origine + DlqFields.ERROR, FAILED_AT, SERVICE, ORIGINAL_ID.
//  7. Arret propre : implementer SmartLifecycle (stop() interrompt la boucle, termine le lot en cours).
//  8. Metriques (voir StreamLagMetrics) : pending et lag par groupe.
//
// Pieges classiques :
//  - NE JAMAIS acquitter avant que la persistance ait reussi (sinon perte silencieuse).
//  - Nom de consumer unique par instance (service + hostname), sinon deux instances se volent les messages.
//  - Utiliser StringRedisTemplate (serialiseurs String) : les champs du stream sont des textes.
//  - BLOCK doit rester inferieur au timeout de commande Redis (spring.data.redis.timeout).
//  - Une erreur sur UN message ne doit pas arreter le consumer : journaliser, router, continuer.
//
// Preuve attendue (etape 4) : arreter storage-service pendant que le simulateur tourne, observer le lag
// monter (XINFO GROUPS), relancer, constater la reprise sans perte.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import java.util.List;
// import org.springframework.context.SmartLifecycle;
// import org.springframework.data.redis.core.StringRedisTemplate;
//
// public abstract class AbstractStreamConsumer implements SmartLifecycle {
//
//     protected final StringRedisTemplate redis;
//     protected final StreamProperties props;
//     private volatile boolean running;
//
//     protected AbstractStreamConsumer(StringRedisTemplate redis, StreamProperties props) {
//         this.redis = redis;
//         this.props = props;
//     }
//
//     /** Traite un lot ; lever une exception = aucun ACK (le lot sera retente). */
//     protected abstract void processBatch(List<EnergyMeasurement> batch);
//
//     /** Nom du service pour le champ DlqFields.SERVICE. */
//     protected abstract String serviceName();
//
//     @Override public void start() { /* ensureGroup(); lancer pollLoop() et reclaimLoop() dans des threads */ running = true; }
//     @Override public void stop()  { /* interrompre les boucles proprement */ running = false; }
//     @Override public boolean isRunning() { return running; }
//
//     private void ensureGroup() { /* XGROUP CREATE ... MKSTREAM, ignorer BUSYGROUP */ }
//     private void pollLoop()    { /* XREADGROUP > ; decoder ; processBatch ; XACK ; DLQ si illisible */ }
//     private void reclaimLoop() { /* XAUTOCLAIM ; compteur de livraisons ; DLQ si > maxDeliveries */ }
//     private void sendToDlq(String originalId, java.util.Map<String, String> fields, String error) { /* XADD dlq */ }
// }
