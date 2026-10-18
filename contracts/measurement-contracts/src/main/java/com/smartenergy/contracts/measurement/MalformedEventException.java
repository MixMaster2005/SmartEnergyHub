package com.smartenergy.contracts.measurement;

/**
 * Evenement du stream illisible (champ obligatoire manquant, nombre invalide, version non supportee).
 * Le consommateur l'envoie IMMEDIATEMENT en DLQ (pas de retry : il ne sera jamais valide).
 */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) { super(message); }

    public MalformedEventException(String message, Throwable cause) { super(message, cause); }
}
