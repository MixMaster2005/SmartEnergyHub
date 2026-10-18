package com.smartenergy.contracts.error;

/** Une violation de champ dans le tableau "errors" d'une reponse 400. */
public record FieldViolation(String field, String message) {}
