package com.smartenergy.contracts.error;

import java.util.Locale;

/**
 * Catalogue unique des codes d'erreur (CDC section 5.6). Le champ "code" des reponses
 * application/problem+json vaut {@link #name()}.
 * Les violations de plage temporelle (trop ancien, futur) renvoient 400, comme dans le guide.
 */
public enum ErrorCode {
    VALIDATION_ERROR(400, "Requete invalide"),
    MALFORMED_JSON(400, "JSON mal forme"),
    MEASUREMENT_TOO_OLD(400, "Mesure trop ancienne"),
    MEASUREMENT_IN_FUTURE(400, "Mesure dans le futur"),
    INVALID_PARAMETER(400, "Parametre invalide"),
    RANGE_TOO_LARGE(400, "Plage de dates trop large"),
    SENSOR_NOT_FOUND(404, "Capteur introuvable"),
    MEASUREMENT_NOT_FOUND(404, "Mesure introuvable"),
    NO_DATA(404, "Aucune donnee pour la periode"),
    RESOURCE_NOT_FOUND(404, "Ressource introuvable"),
    SENSOR_ALREADY_EXISTS(409, "Capteur deja existant"),
    VERSION_CONFLICT(409, "Conflit de version"),
    METHOD_NOT_ALLOWED(405, "Methode HTTP non autorisee"),
    PAYLOAD_TOO_LARGE(413, "Corps de requete trop volumineux"),
    UNSUPPORTED_MEDIA_TYPE(415, "Type de contenu non supporte"),
    INTERNAL_ERROR(500, "Erreur interne"),
    FORECAST_DISABLED(501, "Module de prevision inactif"),
    BROKER_UNAVAILABLE(503, "Broker indisponible");

    private final int status;
    private final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() { return status; }

    public String title() { return title; }

    /** Segment d'URL du champ "type" : VALIDATION_ERROR -> validation-error. */
    public String slug() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /**
     * URI du champ "type" (RFC 9457) : base commune a toutes les erreurs REST, utilisee par le
     * GlobalExceptionHandler de platform-web et par les filtres qui produisent un Problem
     * Details hors du DispatcherServlet (ex. BodySizeLimitFilter du collector).
     */
    public String typeUri() { return "https://smartenergy.local/problems/" + slug(); }
}
