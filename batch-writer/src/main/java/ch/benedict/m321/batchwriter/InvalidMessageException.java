package ch.benedict.m321.batchwriter;

/**
 * Wird geworfen, wenn eine Nachricht aus der Queue dauerhaft unbrauchbar ist: kaputtes
 * JSON oder ein fehlendes Pflichtfeld.
 *
 * Es gibt diese eigene Ausnahme, damit der Listener zwei Faelle unterscheiden kann.
 * Ein Wiederholen hilft hier NIE, egal wie oft man es versucht - deshalb geht eine
 * solche Nachricht nach chat.dlq. Ein Datenbankausfall ist das Gegenteil: dort hilft
 * Wiederholen immer, man muss nur warten.
 */
public class InvalidMessageException extends RuntimeException {

    /**
     * Nimmt den Grund als Text, damit im Log und in der DLQ nachvollziehbar steht,
     * welches Feld gefehlt hat.
     */
    public InvalidMessageException(String reason) {
        super(reason);
    }

    /**
     * Zusaetzlich mit der ausloesenden Ausnahme, damit der Stapel nicht verloren geht,
     * wenn Jackson das JSON nicht lesen konnte.
     */
    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
