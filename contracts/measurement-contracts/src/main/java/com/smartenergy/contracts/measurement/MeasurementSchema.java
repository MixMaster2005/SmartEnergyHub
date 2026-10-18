package com.smartenergy.contracts.measurement;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Version du schema d'evenement et format de date. Regles de compatibilite : voir COMPATIBILITY.md.
 */
public final class MeasurementSchema {
    private MeasurementSchema() {}

    /** Version ecrite par les producteurs dans le champ schemaVersion. */
    public static final int CURRENT_VERSION = 1;

    /** Plus ancienne version encore acceptee par les consommateurs. */
    public static final int MIN_SUPPORTED_VERSION = 1;

    /** Format des dates dans le stream : UTC, millisecondes, suffixe Z (ex. 2026-09-30T08:15:00.000Z). */
    public static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
}
