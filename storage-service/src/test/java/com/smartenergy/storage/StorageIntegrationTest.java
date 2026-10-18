package com.smartenergy.storage;

// ========================================================================
// TODO(tests) - Tests d'integration du storage (Testcontainers Redis + Cassandra)
// ========================================================================
// Guide : strategie de tests (Redis -> Storage, Storage -> Cassandra, lecture via API). CDC : scenarios 3, 5, 6, 8.
// Demarrer Cassandra + Redis (Testcontainers), executer scripts/init-cassandra.cql, lancer l'application, puis :
//  - publier un evenement dans le stream (via MeasurementEventCodec) -> retrouve via GET /api/measurements/{id} ;
//  - meme evenement publie deux fois -> une seule ligne ;
//  - evenement illisible -> present dans energy-measurements-dlq, le flux continue ;
//  - evenement ancien apres un recent -> latest inchange (USING TIMESTAMP) ;
//  - arreter le consumer, publier N evenements, le relancer -> N lignes en base (scenarios 5 et 6).
// Cassandra est lent a demarrer : prevoir un timeout genereux et reutiliser le conteneur entre les tests.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.testcontainers.containers.CassandraContainer;
// import org.testcontainers.containers.GenericContainer;
// import org.testcontainers.junit.jupiter.Testcontainers;
//
// @Testcontainers
// class StorageIntegrationTest {
//     // static CassandraContainer<?> cassandra = new CassandraContainer<>("cassandra:5");
//     // static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
//     // @Test void mesurePubliee_estRetrouveeViaApi() { }
//     // @Test void doublon_neCreeQuUneLigne() { }
//     // @Test void evenementIllisible_vaEnDlq() { }
// }
