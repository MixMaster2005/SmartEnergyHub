package com.smartenergy.contracts.error;

import java.util.List;

/**
 * Forme JSON d'une erreur (RFC 9457 + extensions). Utile cote client / tests pour desserialiser
 * une reponse d'erreur. Cote serveur, platform-web produit cette forme via ProblemDetail.
 */
public record ApiProblem(String type, String title, int status, String code, String detail,
                         String traceId, List<FieldViolation> errors) {}
