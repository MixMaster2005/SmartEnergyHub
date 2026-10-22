package com.smartenergy.platform.stream;

import com.smartenergy.contracts.measurement.DlqFields;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.contracts.measurement.MalformedEventException;
import com.smartenergy.contracts.measurement.MeasurementEventCodec;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Boucle de consommation Redis Streams partagee par tous les consommateurs du stream
 * ({@code storage-group}, {@code analytics-group}) : CDC section 5.3, decisions D-02, D-05, D-22.
 *
 * <p>La sous-classe ne fournit que {@link #processBatch(List)} (persistance du lot) et
 * {@link #serviceName()} ; la lecture, l'acquittement, la reprise et la DLQ sont ici, ecrites une seule fois.
 *
 * <p>Garanties : livraison at-least-once, aucun message perdu, aucun consommateur bloque.
 *
 * <ul>
 *   <li>Groupe cree de facon idempotente a l'initialisation ({@code XGROUP CREATE ... 0 MKSTREAM},
 *       l'erreur {@code BUSYGROUP} est ignoree).</li>
 *   <li>Lecture {@code XREADGROUP COUNT batchSize BLOCK blockMs ... >} dans un thread dedie.</li>
 *   <li>{@code XACK} uniquement apres reussite de {@link #processBatch(List)} ; sinon le lot reste
 *       {@code pending} et sera rejoue.</li>
 *   <li>Structure illisible ({@link MalformedEventException}) : DLQ immediate puis {@code XACK}.</li>
 *   <li>Reprise : au demarrage puis toutes les {@code claimInterval}, {@code XPENDING} detail suivi de
 *       {@code XCLAIM} (equivalent d'{@code XAUTOCLAIM}, non expose par Spring Data Redis : decision D-22)
 *       sur les messages inactifs depuis plus de {@code claimIdle} ; au-dela de {@code maxDeliveries}
 *       livraisons sans acquittement, passage en DLQ.</li>
 *   <li>Arret propre via {@link SmartLifecycle} : les boucles s'interrompent et le lot en cours termine.</li>
 * </ul>
 *
 * <p>Pieges respectes : consumer unique par instance (service + hostname), {@code StringRedisTemplate}
 * (champs texte), {@code blockMs} inferieur au timeout de commande Redis, une erreur sur un message
 * n'arrete jamais la boucle.
 */
public abstract class AbstractStreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AbstractStreamConsumer.class);

    /** Delai entre deux tentatives de creation de groupe quand Redis est indisponible. */
    private static final long GROUP_RETRY_MS = 2_000L;
    /** Delai de repli quand Redis est inaccessible ou qu'une lecture echoue. */
    private static final long REDIS_BACKOFF_MS = 1_000L;
    /** Nombre maximal de caracteres conserve pour la cause dans la DLQ. */
    private static final int MAX_ERROR_LENGTH = 500;
    /** Nombre de messages scannes par passe de reprise. */
    private static final int MIN_RECLAIM_SCAN = 100;

    protected final StringRedisTemplate redis;
    protected final StreamProperties props;

    private final StreamOperations<String, String, String> stream;
    private final Object groupLock = new Object();
    private final Object batchLock = new Object();

    private volatile boolean running;
    private volatile boolean groupReady;
    private String consumerName;
    private Thread pollThread;
    private Thread reclaimThread;

    protected AbstractStreamConsumer(StringRedisTemplate redis, StreamProperties props) {
        this.redis = redis;
        this.props = props;
        this.stream = redis.opsForStream();
    }

    /** Traite un lot ; lever une exception = aucun ACK (le lot sera rejoue). */
    protected abstract void processBatch(List<EnergyMeasurement> batch);

    /** Nom du service pour le champ {@code DlqFields.SERVICE}. */
    protected abstract String serviceName();

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (props.group() == null || props.group().isBlank()) {
            throw new IllegalStateException(
                    "smartenergy.stream.group doit etre defini pour consommer le stream (" + serviceName() + ")");
        }
        consumerName = resolveConsumerName();
        running = true;
        ensureGroup();
        pollThread = new Thread(this::pollLoop, "stream-poll-" + props.group());
        reclaimThread = new Thread(this::reclaimLoop, "stream-reclaim-" + props.group());
        pollThread.setDaemon(true);
        reclaimThread.setDaemon(true);
        pollThread.start();
        reclaimThread.start();
        log.info("{} consomme {} (groupe {}, consumer {})",
                serviceName(), props.name(), props.group(), consumerName);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        groupReady = false;
        interrupt(pollThread);
        interrupt(reclaimThread);
        join(pollThread);
        join(reclaimThread);
        log.info("{} a arrete la consommation (groupe {})", serviceName(), props.group());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Nom du consumer : service + hostname, surchargeable par {@code smartenergy.stream.consumer-name}. */
    private String resolveConsumerName() {
        if (props.consumerName() != null && !props.consumerName().isBlank()) {
            return props.consumerName();
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "inconnu";
        }
        return serviceName() + "-" + host;
    }

    /** XGROUP CREATE idempotent ; BUSYGROUP = groupe deja cree. Bloque la reprise tant que Redis est absent. */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        synchronized (groupLock) {
            if (groupReady) {
                return;
            }
            try {
                stream.createGroup(props.name(), ReadOffset.from("0"), props.group());
                groupReady = true;
                log.info("groupe {} cree sur {}", props.group(), props.name());
            } catch (RuntimeException e) {
                if (containsBusyGroup(e)) {
                    groupReady = true;
                    log.debug("groupe {} deja present sur {}", props.group(), props.name());
                } else {
                    log.warn("creation du groupe {} impossible (Redis disponible ?) : {}",
                            props.group(), e.getMessage());
                    sleep(GROUP_RETRY_MS);
                }
            }
        }
    }

    /** XREADGROUP ... > : seuls les nouveaux messages sont livres a ce consumer. */
    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                ensureGroup();
                if (!groupReady) {
                    continue;
                }
                List<MapRecord<String, String, String>> records = stream.read(
                        Consumer.from(props.group(), consumerName),
                        StreamReadOptions.empty()
                                .count(props.batchSize())
                                .block(Duration.ofMillis(props.blockMs())),
                        StreamOffset.create(props.name(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                handle(records);
            } catch (RuntimeException e) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    break; // arret en cours : la lecture bloquante a ete annulee, ce n'est pas une erreur
                }
                log.warn("{} : lecture du stream en echec, nouvelle tentative : {}",
                        serviceName(), e.getMessage());
                sleep(REDIS_BACKOFF_MS);
            }
        }
    }

    /** Reprise des messages inactifs (XPENDING + XCLAIM, decision D-22) puis traitement comme un lot normal. */
    private void reclaimLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            if (groupReady) {
                try {
                    reclaim();
                } catch (RuntimeException e) {
                    if (!running) {
                        break;
                    }
                    log.warn("{} : reprise des messages inactifs en echec : {}", serviceName(), e.getMessage());
                }
            }
            sleep(props.claimInterval().toMillis());
        }
    }

    private void reclaim() {
        long scan = Math.max((long) props.batchSize() * 2, MIN_RECLAIM_SCAN);
        PendingMessages pending = stream.pending(props.name(), props.group(), Range.unbounded(), scan);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<RecordId> candidates = new ArrayList<>();
        Map<RecordId, Long> deliveries = new HashMap<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(props.claimIdle()) < 0) {
                continue;
            }
            candidates.add(message.getId());
            deliveries.put(message.getId(), message.getTotalDeliveryCount());
        }
        if (candidates.isEmpty()) {
            return;
        }

        List<MapRecord<String, String, String>> claimed = stream.claim(
                props.name(),
                props.group(),
                consumerName,
                props.claimIdle(),
                candidates.toArray(RecordId[]::new));
        if (claimed == null) {
            claimed = List.of();
        }

        List<MapRecord<String, String, String>> toProcess = new ArrayList<>(claimed.size());
        for (MapRecord<String, String, String> record : claimed) {
            long count = deliveries.getOrDefault(record.getId(), 0L);
            if (count >= props.maxDeliveries()) {
                log.warn("{} : message {} livre {} fois sans ACK, envoye en DLQ",
                        serviceName(), record.getId(), count);
                sendToDlq(record.getId(), record.getValue(),
                        "trop de livraisons sans acquittement : " + count);
            } else {
                toProcess.add(record);
            }
        }

        Set<RecordId> claimedIds = new HashSet<>();
        for (MapRecord<String, String, String> record : claimed) {
            claimedIds.add(record.getId());
        }
        purgeTrimmed(candidates, claimedIds);

        if (!toProcess.isEmpty()) {
            log.debug("{} : reprise de {} messages inactifs", serviceName(), toProcess.size());
            handle(toProcess);
        }
    }

    /**
     * Les entrees listees par XPENDING mais absentes de la reponse XCLAIM ont ete tondues par MAXLEN :
     * elles sont retirees de la PEL, comme le ferait XAUTOCLAIM. Une entree encore presente a ete
     * reprise entre-temps par un autre consumer : on ne touche pas.
     */
    private void purgeTrimmed(List<RecordId> candidates, Set<RecordId> claimedIds) {
        for (RecordId id : candidates) {
            if (claimedIds.contains(id)) {
                continue;
            }
            if (stream.range(props.name(), Range.closed(id.getValue(), id.getValue())).isEmpty()) {
                log.warn("{} : message {} absent du stream (tondu par MAXLEN), retire de la PEL",
                        serviceName(), id);
                acknowledge(List.of(id));
            }
        }
    }

    /** Decodage, traitement du lot et acquittement ; une erreur d'un message n'arrete jamais la boucle. */
    private void handle(List<MapRecord<String, String, String>> records) {
        synchronized (batchLock) {
            List<EnergyMeasurement> batch = new ArrayList<>(records.size());
            List<RecordId> batchIds = new ArrayList<>(records.size());
            for (MapRecord<String, String, String> record : records) {
                try {
                    batch.add(MeasurementEventCodec.fromFields(record.getValue()));
                    batchIds.add(record.getId());
                } catch (MalformedEventException e) {
                    log.warn("{} : evenement illisible {} envoye en DLQ : {}",
                            serviceName(), record.getId(), e.getMessage());
                    sendToDlq(record.getId(), record.getValue(), e.getMessage());
                }
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                processBatch(batch);
                acknowledge(batchIds);
            } catch (Exception e) {
                log.error("{} : lot de {} mesures en echec, aucun ACK (le lot sera rejoue)",
                        serviceName(), batch.size(), e);
            }
        }
    }

    /** XADD dans la DLQ avec les champs d'origine enrichis, puis XACK de l'original. */
    private void sendToDlq(RecordId originalId, Map<String, String> fields, String error) {
        Map<String, String> dlqFields = new LinkedHashMap<>(fields);
        dlqFields.put(DlqFields.ERROR, truncate(error));
        dlqFields.put(DlqFields.FAILED_AT, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        dlqFields.put(DlqFields.SERVICE, serviceName());
        dlqFields.put(DlqFields.ORIGINAL_ID, originalId.getValue());
        try {
            MapRecord<String, String, String> record =
                    StreamRecords.string(dlqFields).withStreamKey(props.dlqName());
            stream.add(record);
            acknowledge(List.of(originalId));
        } catch (RuntimeException e) {
            log.error("{} : envoi en DLQ impossible pour {}, le message reste en attente",
                    serviceName(), originalId, e.getMessage());
        }
    }

    private void acknowledge(List<RecordId> ids) {
        if (ids.isEmpty()) {
            return;
        }
        stream.acknowledge(props.name(), props.group(), ids.toArray(RecordId[]::new));
    }

    private static boolean containsBusyGroup(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(props.blockMs() + 5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("{} : le thread {} n'a pas pu s'arreter a temps", serviceName(), thread.getName());
        }
    }
}
