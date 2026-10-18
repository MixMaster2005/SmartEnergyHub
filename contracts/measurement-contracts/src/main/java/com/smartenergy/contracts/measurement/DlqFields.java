package com.smartenergy.contracts.measurement;

/** Champs ajoutes aux champs d'origine lors d'un envoi dans energy-measurements-dlq. */
public final class DlqFields {
    private DlqFields() {}

    public static final String ERROR = "error";
    public static final String FAILED_AT = "failedAt";
    public static final String SERVICE = "service";
    public static final String ORIGINAL_ID = "originalId";
}
