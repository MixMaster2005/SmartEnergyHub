package com.smartenergy.storage.service;

// ========================================================================
// TODO(etape-8) - Curseur de pagination opaque (jour + dernier measured_at)
// ========================================================================
// Guide : etape 8. CDC : section 5.4 (nextCursor = jeton opaque, null en fin de liste).
// Pourquoi pas le paging state du pilote ? L'historique peut traverser PLUSIEURS partitions (une par jour) :
// un curseur maison "jour|epochMillis" est plus simple a raisonner.
//
// A faire :
//  - encode(LocalDate day, Instant lastMeasuredAt) -> base64 URL-safe de "yyyy-MM-dd|epochMillis".
//  - decode(String cursor) -> (day, instant) ; curseur invalide -> ApiException(INVALID_PARAMETER).
//  - Test unitaire : aller-retour, curseur corrompu.
//
// -- Squelette a activer : decommenter (Ctrl+/) puis completer --
//
// public record HistoryCursor(java.time.LocalDate day, java.time.Instant lastMeasuredAt) {
//     public String encode() { /* base64url(day + "|" + lastMeasuredAt.toEpochMilli()) */ throw new UnsupportedOperationException(); }
//     public static HistoryCursor decode(String cursor) { /* ... ou ApiException(INVALID_PARAMETER) */ throw new UnsupportedOperationException(); }
// }
