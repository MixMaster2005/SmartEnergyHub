package com.smartenergy.storage.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.smartenergy.contracts.measurement.EnergyMeasurement;
import com.smartenergy.storage.entity.LatestMeasurementEntity;
import com.smartenergy.storage.entity.MeasurementByDayEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.StatementFactory;
import org.springframework.stereotype.Component;

/**
 * Ecriture idempotente des mesures dans les deux tables Cassandra (CDC sections 6.1 et 7.3,
 * decisions D-08, D-09, D-10, D-23).
 *
 * <p>Pour chaque mesure du lot : un upsert de {@code measurements_by_sensor_day} (idempotent par
 * cle primaire : rejouer le meme message ecrase la meme ligne avec les memes valeurs) et un upsert
 * de {@code latest_measurement_by_sensor} porte par {@code USING TIMESTAMP} = temps d'EVENEMENT en
 * microsecondes (D-09) : une mesure en retard n'ecrase jamais une plus recente.
 *
 * <p><b>Ecriture asynchrone (decision D-23)</b> : les statements sont construits par Spring
 * (mapping des entites et omission des colonnes null, D-10), soumis au driver via
 * {@code CqlSession.executeAsync} puis attendus d'un seul bloc {@code allOf().join()}. La premiere
 * erreur est propagee : le consumer n'acquitte pas, le lot entier sera rejoue (sans danger, le
 * writer est idempotent). Pas de batch Cassandra "logged" entre les deux tables : deux upserts
 * independants suffisent.
 *
 * <p>Metriques : {@code energy_persist_duration_seconds} (Timer, un echantillon par lot) et
 * {@code energy_persist_errors_total} (Counter, incrementee a chaque lot en echec).
 */
@Component
public class CassandraMeasurementWriter {

    private static final String PERSIST_TIMER = "energy_persist_duration";
    private static final String PERSIST_ERRORS = "energy_persist_errors_total";

    private final CqlSession session;
    private final StatementFactory statements;
    private final MeterRegistry registry;
    private final Timer persistTimer;
    private final Counter persistErrors;

    public CassandraMeasurementWriter(CassandraTemplate cassandra, CqlSession session, MeterRegistry registry) {
        this.session = session;
        this.statements = new StatementFactory(cassandra.getConverter());
        this.registry = registry;
        this.persistTimer = Timer.builder(PERSIST_TIMER).register(registry);
        this.persistErrors = registry.counter(PERSIST_ERRORS);
    }

    /**
     * Persiste un lot dans les deux tables : ecritures lancees en parallele puis attente groupee
     * (decision D-23).
     *
     * @throws RuntimeException si une ecriture echoue : aucun ACK cote consumer, le lot sera rejoue
     */
    public void writeBatch(List<EnergyMeasurement> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Timer.Sample sample = Timer.start(registry);
        try {
            List<CompletableFuture<AsyncResultSet>> writes = new ArrayList<>(batch.size() * 2);
            for (EnergyMeasurement measurement : batch) {
                writes.add(insert(MeasurementByDayEntity.from(measurement), InsertOptions.empty()));
                writes.add(insert(
                        LatestMeasurementEntity.from(measurement),
                        InsertOptions.builder().timestamp(measurement.timestamp()).build()));
            }
            CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0])).join();
        } catch (RuntimeException e) {
            persistErrors.increment();
            throw unwrap(e);
        } finally {
            sample.stop(persistTimer);
        }
    }

    /** Construit le statement via Spring puis le soumet au driver sans blocage. */
    private CompletableFuture<AsyncResultSet> insert(Object entity, InsertOptions options) {
        SimpleStatement statement = statements.insert(entity, options).build();
        return session.executeAsync(statement).toCompletableFuture();
    }

    /** La cause reelle du lot doit remonter telle quelle : c'est elle qui sera journalisee. */
    private static RuntimeException unwrap(RuntimeException error) {
        if (error instanceof CompletionException && error.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return error;
    }
}
