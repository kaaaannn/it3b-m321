package ch.benedict.m321.batchwriter;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Macht aus dem Rumpf einer AMQP-Nachricht einen IncomingMessage-Datensatz und prueft,
 * dass alle Pflichtfelder da sind.
 *
 * Diese Klasse ist die wichtigste Entscheidung des ganzen Dienstes. Sie liest den Rumpf
 * SELBST und nennt Jackson die Zielklasse im Code. Der Weg ueber einen typgesteuerten
 * Wandler waere kuerzer, haette aber zwei Nachteile:
 *
 * 1. Er braucht den Header __TypeId__, den der chat-service nebenbei mitschickt. Darin
 *    steht ein Java-Klassenname. Wird die Klasse dort umbenannt, bricht dieser Dienst,
 *    ohne dass ihn jemand angefasst hat.
 * 2. Legt jemand eine Nachricht von Hand in die Queue, nur mit content_type
 *    application/json, fehlt dieser Header. Genau das macht Szenario S5 der Bewertung.
 *
 * Darum: kein Typ-Header, kein Vertrauen auf den Absender, nur das JSON.
 */
@Component
public class MessagePayloadReader {

    private static final Logger log = LoggerFactory.getLogger(MessagePayloadReader.class);

    private final ObjectMapper objectMapper;

    /**
     * Spring reicht den ObjectMapper herein, den Spring Boot selbst gebaut hat. Der ist
     * so eingestellt, dass er Zeitpunkte wie "2026-09-11T12:36:52.816460100Z" versteht
     * und unbekannte Felder ueberliest, statt abzubrechen.
     */
    public MessagePayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Liest die Bytes aus der Queue und gibt den geprueften Datensatz zurueck.
     * Wirft InvalidMessageException, wenn die Nachricht dauerhaft unbrauchbar ist.
     */
    public IncomingMessage read(byte[] body) {
        IncomingMessage message = parse(body);
        checkRequiredFields(message);
        return message;
    }

    /**
     * Wandelt die Bytes in den Datensatz. Scheitert Jackson daran, ist der Rumpf kein
     * gueltiges JSON, und das wird nie besser.
     */
    private IncomingMessage parse(byte[] body) {
        try {
            return objectMapper.readValue(body, IncomingMessage.class);
        } catch (IOException fehler) {
            log.warn("Nachricht ist kein lesbares JSON: {}", fehler.getMessage());
            throw new InvalidMessageException("Rumpf ist kein lesbares JSON", fehler);
        }
    }

    /**
     * Prueft die fuenf Pflichtfelder aus dem Vertrag. Jede Pruefung steht fuer sich,
     * damit die Fehlermeldung sagt, WELCHES Feld fehlt - das hilft beim Nachschauen
     * in der DLQ.
     */
    private void checkRequiredFields(IncomingMessage message) {
        if (message.id() == null) {
            throw new InvalidMessageException("Feld id fehlt oder ist keine UUID");
        }
        if (message.roomId() == null) {
            throw new InvalidMessageException("Feld roomId fehlt oder ist keine UUID");
        }
        if (message.sender() == null || message.sender().isBlank()) {
            throw new InvalidMessageException("Feld sender fehlt oder ist leer");
        }
        if (message.text() == null || message.text().isBlank()) {
            throw new InvalidMessageException("Feld text fehlt oder ist leer");
        }
        if (message.sentAt() == null) {
            throw new InvalidMessageException("Feld sentAt fehlt oder ist kein Zeitpunkt");
        }
    }
}
