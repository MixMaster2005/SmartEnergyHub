package com.smartenergy.storage.service;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Curseur de pagination opaque de GET /api/measurements/{sensorId} (CDC section 5.4).
 *
 * <p>Le jeton encode {@code jour|epochMillis} (heure UTC) du DERNIER element renvoye : le appel
 * suivant reprend juste apres cette limite. Encode en base64 URL-safe (sans padding) pour passer
 * tel quel dans une query string.
 *
 * <p><b>Pourquoi pas le paging state du pilote Cassandra ?</b> L'historique peut traverser
 * PLUSIEURS partitions (une par jour, decision D-08) : le paging state n'est valable que pour une
 * requete, pas pour une serie de requetes. Un curseur maison "jour|epochMillis" donne un jeton
 * stable, indépendant du nombre de jours, et rejoue n'importe quelle page (decision du guide,
 * section 5.4 : "jeton opaque").
 *
 * <p>Un curseur corrompu ou d'un autre capteur ne doit pas faire echouer la plateforme :
 * {@link #decode(String)} leve {@code ApiException(INVALID_PARAMETER)} (400), jamais une
 * NullPointerException ni un 500.
 *
 * @param day jour UTC du dernier element renvoye
 * @param lastMeasuredAt instant (millisecondes) du dernier element renvoye
 */
public record HistoryCursor(LocalDate day, Instant lastMeasuredAt) {

    private static final String SEPARATOR = "|";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * Encode le curseur en base64 URL-safe de {@code "yyyy-MM-dd|epochMillis"}.
     *
     * <p>Base64 "URL" (avec - et _ au lieu de + et /) pour eviter tout echappement de caracteres
     * dans une query string ; sans padding, le jeton reste court.
     */
    public String encode() {
        String plain = day + SEPARATOR + lastMeasuredAt.toEpochMilli();
        return ENCODER.encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Décode un jeton opaque renvoye par un appel precedent.
     *
     * @param cursor jeton non null (un curseur null = premier appel, jamais passe ici)
     * @return le curseur equivalent
     * @throws ApiException(INVALID_PARAMETER) si le jeton est null, vide, malforme, base64
     *     invalide ou ne contient pas une date et un entier valides
     */
    public static HistoryCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "Le curseur ne doit pas etre vide");
        }
        String plain;
        try {
            plain = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "Curseur de pagination invalide (base64 illisible)");
        }
        String[] parts = plain.split("\\" + SEPARATOR, 2);
        if (parts.length != 2) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "Curseur de pagination invalide (format attendu : jour|millis)");
        }
        try {
            return new HistoryCursor(LocalDate.parse(parts[0]), Instant.ofEpochMilli(Long.parseLong(parts[1])));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "Curseur de pagination invalide (date ou millis illisibles)");
        }
    }
}
