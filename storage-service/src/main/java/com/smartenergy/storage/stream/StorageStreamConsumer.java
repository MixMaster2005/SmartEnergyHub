package com.smartenergy.storage.stream;

// ========================================================================
// TODO(etape-7) - Consommateur Redis du storage-group
// ========================================================================
// Guide : etape 7 (Storage Service). CDC : section 7.3 ; scenarios 5 et 6 (arret / reprise).
// Etend AbstractStreamConsumer (platform-stream) : la boucle XREADGROUP / ACK / reprise / DLQ y est deja
// decrite. Ici : seulement ce qui est specifique au stockage.
//
// A faire :
//  - Annoter @Component ; passer StreamProperties (group = storage-group, via application.yml).
//  - processBatch(List<EnergyMeasurement>) : appeler CassandraMeasurementWriter.writeBatch(batch).
//    Une exception = pas d'ACK = le lot sera retente (le writer est idempotent : sans danger).
//  - serviceName() renvoie "storage-service".
//  - Critere de validation : une mesure envoyee au collector est retrouvee dans Cassandra.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import com.smartenergy.contracts.measurement.EnergyMeasurement;
// import com.smartenergy.platform.stream.AbstractStreamConsumer;
// import com.smartenergy.platform.stream.StreamProperties;
// import com.smartenergy.storage.service.CassandraMeasurementWriter;
// import java.util.List;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.stereotype.Component;
//
// @Component
// public class StorageStreamConsumer extends AbstractStreamConsumer {
//     private final CassandraMeasurementWriter writer;
//
//     public StorageStreamConsumer(StringRedisTemplate redis, StreamProperties props, CassandraMeasurementWriter writer) {
//         super(redis, props);
//         this.writer = writer;
//     }
//
//     @Override protected void processBatch(List<EnergyMeasurement> batch) { /* writer.writeBatch(batch); */ }
//     @Override protected String serviceName() { return "storage-service"; }
// }
