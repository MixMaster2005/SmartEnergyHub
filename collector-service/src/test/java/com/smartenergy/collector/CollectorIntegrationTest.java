package com.smartenergy.collector;

// ========================================================================
// TODO(tests) - Test d'integration du collector (Testcontainers Redis)
// ========================================================================
// Guide : strategie de tests (integration API -> Redis). CDC : section 12, scenarios 1, 2, 8, 9.
// Demarrer un Redis jetable avec Testcontainers, lancer l'application (@SpringBootTest, port aleatoire),
// puis verifier :
//  - scenario 1 : POST valide -> 202, en-tete X-Event-Id, XLEN +1 ;
//  - scenario 2 : sensorId absent -> 400 application/problem+json, XLEN inchange ;
//  - corps avec puissance en chaine ("3.72") -> 400 MALFORMED_JSON (pas de coercion) ;
//  - mesure vieille de 25 h -> 400 MEASUREMENT_TOO_OLD ;
//  - scenario 8 : meme mesure deux fois -> 202 et 202 ;
//  - scenario 9 : conteneur Redis arrete -> 503 avec Retry-After ;
//  - le contenu du stream est valide contre contracts/schemas/measurement-event.schema.json.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// import org.junit.jupiter.api.Test;
// import org.springframework.boot.test.context.SpringBootTest;
// import org.testcontainers.containers.GenericContainer;
// import org.testcontainers.junit.jupiter.Container;
// import org.testcontainers.junit.jupiter.Testcontainers;
//
// @Testcontainers
// @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// class CollectorIntegrationTest {
//
//     @Container
//     static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
//
//     // @DynamicPropertySource : fournir REDIS_HOST / REDIS_PORT a partir du conteneur
//
//     @Test void mesureValide_donne202_et_unEvenementDansLeStream() { }
//     @Test void sensorIdAbsent_donne400_et_aucunEvenement() { }
//     @Test void redisArrete_donne503() { }
// }
