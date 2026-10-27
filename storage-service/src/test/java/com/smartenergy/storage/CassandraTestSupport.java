package com.smartenergy.storage;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Aide partagee aux tests d'integration Cassandra (Testcontainers, decision D-19) : conteneur
 * attendu sur le message de demarrage, session connectee dessus et application de
 * {@code scripts/init-cassandra.cql} (decision D-11 : le schema vient du script, jamais de Spring).
 */
final class CassandraTestSupport {

    /** Cassandra ouvre 9042 avant le native transport : on retente jusqu'a 3 minutes. */
    private static final Duration SCHEMA_TIMEOUT = Duration.ofMinutes(3);

    private static final long RETRY_DELAY_MS = 2_000L;

    private CassandraTestSupport() {}

    /** Conteneur {@code cassandra:5} qui attend vraiment l'ouverture pour les clients CQL. */
    static CassandraContainer<?> container() {
        return new CassandraContainer<>("cassandra:5")
                // Le port 9042 s'ouvre avant que le native transport soit vraiment pret :
                // attendre le message de demarrage, au-dela du simple check de port.
                .waitingFor(Wait.forLogMessage(".*Starting listening for CQL clients.*", 1));
    }

    /** Session connectee au conteneur, dans le keyspace {@code smartenergy}. */
    static CqlSession connect(CassandraContainer<?> container) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(container.getHost(), container.getFirstMappedPort()))
                .withLocalDatacenter("datacenter1")
                .withKeyspace("smartenergy")
                .build();
    }

    /**
     * Applique {@code scripts/init-cassandra.cql} (idempotent, {@code IF NOT EXISTS}) en retenant
     * jusqu'a ce que Cassandra reponde : le script est rejoue a chaque test qui en a besoin.
     */
    static void applySchema(CassandraContainer<?> container) throws Exception {
        String cql = Files.readString(Path.of("..", "scripts", "init-cassandra.cql"));
        Instant deadline = Instant.now().plus(SCHEMA_TIMEOUT);

        while (true) {
            try (CqlSession session = CqlSession.builder()
                    .addContactPoint(new InetSocketAddress(
                            container.getHost(), container.getFirstMappedPort()))
                    .withLocalDatacenter("datacenter1")
                    .build()) {
                for (String statement : cql.split(";")) {
                    String stripped = stripComments(statement).trim();
                    if (!stripped.isEmpty()) {
                        session.execute(stripped);
                    }
                }
                return;
            } catch (Exception e) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Cassandra non pret apres 3 min", e);
                }
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }

    private static String stripComments(String cql) {
        StringBuilder out = new StringBuilder();
        for (String line : cql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
