package com.smartenergy.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Point d'entree unique de la plateforme (extension EXT-GW).
 *
 * <p>Le routage, le CORS, l'accueil frontend et le Swagger agrege sont
 * configures dans {@code application.yml} : une regle declarative par route,
 * sans logique applicative.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
