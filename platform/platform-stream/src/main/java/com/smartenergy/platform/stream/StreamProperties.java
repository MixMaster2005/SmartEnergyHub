package com.smartenergy.platform.stream;

import com.smartenergy.contracts.measurement.StreamContract;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parametres du stream (prefixe smartenergy.stream.*). Valeurs par defaut = contrat (CDC section 5.3).
 * Chaque consommateur fixe son "group" (storage-group ou analytics-group) dans son application.yml.
 */
@ConfigurationProperties(prefix = "smartenergy.stream")
public record StreamProperties(
        @DefaultValue(StreamContract.STREAM) String name,
        @DefaultValue(StreamContract.DLQ_STREAM) String dlqName,
        @DefaultValue("500000") long maxLen,
        @DefaultValue("") String group,
        /** Vide = nom du service + hostname (unique par instance). */
        @DefaultValue("") String consumerName,
        @DefaultValue("50") int batchSize,
        @DefaultValue("2000") long blockMs,
        /** Un message en attente depuis plus longtemps est reclame (XAUTOCLAIM). */
        @DefaultValue("PT60S") Duration claimIdle,
        @DefaultValue("PT30S") Duration claimInterval,
        /** Au-dela : DLQ. */
        @DefaultValue("5") int maxDeliveries) {}
