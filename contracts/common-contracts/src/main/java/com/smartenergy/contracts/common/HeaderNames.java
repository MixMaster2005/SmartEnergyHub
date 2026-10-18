package com.smartenergy.contracts.common;

/** En-tetes HTTP communs a tous les services. */
public final class HeaderNames {
    private HeaderNames() {}

    /** Correlation : genere par le premier service si absent, renvoye dans la reponse, copie dans traceId. */
    public static final String REQUEST_ID = "X-Request-Id";

    /** Renvoye par le collector (202) : identifiant deterministe de l'evenement publie. */
    public static final String EVENT_ID = "X-Event-Id";
}
