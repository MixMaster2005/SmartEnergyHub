package com.smartenergy.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Curseur de pagination : aller-retour encode/decode et rejet des jetons corrompus
 * (etape 8, CDC section 5.4).
 */
class HistoryCursorTest {

    private static final HistoryCursor CURSOR =
            new HistoryCursor(LocalDate.of(2026, 9, 30), Instant.parse("2026-09-30T08:15:01.500Z"));

    @Test
    void encodeDecode_allerRetour_identique() {
        HistoryCursor decoded = HistoryCursor.decode(CURSOR.encode());
        assertEquals(CURSOR.day(), decoded.day());
        assertEquals(CURSOR.lastMeasuredAt(), decoded.lastMeasuredAt());
    }

    @Test
    void encode_estUrlSafe_sansPadding() {
        String encoded = CURSOR.encode();
        // base64 URL-safe : ni '+' ni '/' ; sans '=' de padding.
        assertEquals(-1, encoded.indexOf('+'));
        assertEquals(-1, encoded.indexOf('/'));
        assertEquals(-1, encoded.indexOf('='));
    }

    @Test
    void encode_changeAvecLinstant_curseurSansCollision() {
        HistoryCursor autre = new HistoryCursor(CURSOR.day(), Instant.parse("2026-09-30T08:15:02.000Z"));
        assertNotEquals(CURSOR.encode(), autre.encode());
    }

    @Test
    void decode_base64Invalide_rejette400() {
        ApiException e = assertThrows(ApiException.class, () -> HistoryCursor.decode("@@@pas-du-base64@@@"));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.code());
    }

    @Test
    void decode_base64ValideMaisContenuIllisible_rejette400() {
        // "bonjour" est du base64 valide mais ne contient pas "jour|millis".
        String fake = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("bonjour".getBytes());
        ApiException e = assertThrows(ApiException.class, () -> HistoryCursor.decode(fake));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.code());
    }

    @Test
    void decode_mauvaisFormatDate_rejette400() {
        String fake = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("30-09-2026|1759229701500".getBytes());
        ApiException e = assertThrows(ApiException.class, () -> HistoryCursor.decode(fake));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.code());
    }

    @Test
    void decode_millisNonNumerique_rejette400() {
        String fake = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-09-30|demain".getBytes());
        ApiException e = assertThrows(ApiException.class, () -> HistoryCursor.decode(fake));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.code());
    }

    @Test
    void decode_vide_ouNull_rejette400() {
        assertEquals(ErrorCode.INVALID_PARAMETER,
                assertThrows(ApiException.class, () -> HistoryCursor.decode(null)).code());
        assertEquals(ErrorCode.INVALID_PARAMETER,
                assertThrows(ApiException.class, () -> HistoryCursor.decode("   ")).code());
    }
}
