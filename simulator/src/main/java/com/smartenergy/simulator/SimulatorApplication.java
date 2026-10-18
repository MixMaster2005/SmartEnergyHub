package com.smartenergy.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Application en ligne de commande (pas de serveur web) : se termine a la fin de la duree configuree. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
