package com.smartenergy.storage.stream;

import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.platform.stream.AbstractStreamConsumer;
import com.smartenergy.platform.stream.StreamProperties;
import com.smartenergy.storage.service.CassandraMeasurementWriter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consommateur Redis du {@code storage-group} (CDC section 7.3, decisions D-02, D-22).
 *
 * <p>Etend {@link AbstractStreamConsumer} : la boucle XREADGROUP, l'acquittement, la reprise
 * (XPENDING + XCLAIM) et la DLQ sont deja ecrits dans la classe mere. Ce qui reste specifique au
 * stockage tient en deux points : nom du service (champ {@code service} de la DLQ) et persistance
 * du lot via {@link CassandraMeasurementWriter}.
 *
 * <p>Contrat d'acquittement : une exception levee par le writer = aucun {@code XACK} = le lot
 * reste pending et sera rejoue ; le writer etant idempotent, ce rejeu est sans danger. Arret puis
 * redemarrage du service : aucune mesure perdue (scenarios 5 et 6).
 */
@Component
public class StorageStreamConsumer extends AbstractStreamConsumer {

    private final CassandraMeasurementWriter writer;

    public StorageStreamConsumer(
            StringRedisTemplate redis, StreamProperties props, CassandraMeasurementWriter writer) {
        super(redis, props);
        this.writer = writer;
    }

    /** Persistance du lot : echec = pas d'ACK, le lot sera rejoue. */
    @Override
    protected void processBatch(List<EnergyMeasurement> batch) {
        writer.writeBatch(batch);
    }

    /** Nom du service pour les journaux et le champ {@code DlqFields.SERVICE}. */
    @Override
    protected String serviceName() {
        return "storage-service";
    }
}
