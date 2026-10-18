package com.smartenergy.contracts.measurement;

/** Noms et parametres par defaut du stream Redis (CDC section 5.3). */
public final class StreamContract {
    private StreamContract() {}

    public static final String STREAM = "energy-measurements";
    public static final String DLQ_STREAM = "energy-measurements-dlq";

    public static final String STORAGE_GROUP = "storage-group";
    public static final String ANALYTICS_GROUP = "analytics-group";

    /** Retention approximative : XADD ... MAXLEN ~ 500000 (decision D-06). */
    public static final long DEFAULT_MAXLEN = 500_000L;
    /** Au-dela de ce nombre de livraisons, un message part en DLQ (decision D-05). */
    public static final int DEFAULT_MAX_DELIVERIES = 5;
    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final long DEFAULT_BLOCK_MS = 2000L;
}
