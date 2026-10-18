package com.smartenergy.analytics;

// ========================================================================
// TODO(tests) - Tests d'integration analytics (Testcontainers Redis + MongoDB)
// ========================================================================
// Guide : strategie de tests. CDC : scenarios 4, 8, 11. Demarrer Redis + MongoDB (executer scripts/init-mongo.js
// pour les index), un faux sensor-service (WireMock ou serveur HTTP de test) puis :
//  - publier des evenements -> agregats horaires/quotidiens corrects ;
//  - pic x3 -> anomalie THRESHOLD visible via /anomalies ;
//  - meme evenement deux fois -> compteurs inchanges ;
//  - sensor-service arrete -> traitement avec le seuil par defaut (thresholdSource = DEFAULT).
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.testcontainers.containers.GenericContainer;
// import org.testcontainers.containers.MongoDBContainer;
//
// class AnalyticsIntegrationTest {
//     // static MongoDBContainer mongo = new MongoDBContainer("mongo:7");
//     // static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
// }
