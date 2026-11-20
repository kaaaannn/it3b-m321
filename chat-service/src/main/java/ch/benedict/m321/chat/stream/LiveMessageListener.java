package ch.benedict.m321.chat.stream;

import java.io.IOException;

import ch.benedict.m321.chat.message.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Hoert auf die eigene Live-Queue dieser Instanz und gibt jede Nachricht an die offenen
 * Streams ihres Raums weiter. Damit ist der Weg Exchange -> Instanz -> Stream geschlossen
 * (docs/spec-live-zustellung.md, Abschnitt 3.1).
 *
 * Den Rumpf liest diese Klasse selbst, wie der MessagePayloadReader im batch-writer: Die
 * Zielklasse steht im Code, ein Typ-Header des Absenders wird nicht gebraucht.
 */
@Component
public class LiveMessageListener {

    private static final Logger log = LoggerFactory.getLogger(LiveMessageListener.class);

    private final ObjectMapper objectMapper;
    private final StreamRegistry streamRegistry;

    /**
     * Spring reicht den ObjectMapper von Spring Boot herein (versteht Zeitpunkte wie
     * "2026-11-20T08:00:00Z" und ueberliest unbekannte Felder) und die Registry mit den
     * offenen Verbindungen.
     */
    public LiveMessageListener(ObjectMapper objectMapper, StreamRegistry streamRegistry) {
        this.objectMapper = objectMapper;
        this.streamRegistry = streamRegistry;
    }

    /**
     * Wird fuer jede Nachricht aus der Live-Queue aufgerufen.
     *
     * Der Queue-Name kommt ueber "#{liveQueue.name}" aus der Bean liveQueue, weil er
     * zufaellig ist und erst beim Start feststeht. "concurrency = 1" heisst: genau ein
     * Hoer-Thread. Nur so werden die Nachrichten in der Reihenfolge geschrieben, in der
     * sie in der Queue liegen (Spezifikation 3.1, Punkt 3 und 5). Der eigene Behaelter
     * "liveListenerContainerFactory" sorgt dafuer, dass der Rumpf als rohe Bytes ankommt.
     *
     * Kehrt die Methode normal zurueck, bestaetigt Spring die Nachricht beim Broker.
     */
    @RabbitListener(
            queues = "#{liveQueue.name}",
            concurrency = "1",
            containerFactory = "liveListenerContainerFactory")
    public void receive(byte[] body) {
        Message message = parse(body);

        // Unlesbare Nachrichten werden verworfen, nicht in eine DLQ gelegt: Live-Nachrichten
        // werden nicht aufgehoben. Gespeichert ist die Nachricht - wenn sie gueltig ist -
        // ohnehin ueber chat.persist.
        if (message == null) {
            return;
        }

        log.debug("Live-Nachricht {} fuer Raum {} erhalten", message.id(), message.roomId());
        streamRegistry.publish(message);
    }

    /**
     * Wandelt die Bytes in eine Message. Gibt null zurueck, wenn das nicht geht.
     *
     * Ohne id oder roomId ist eine Nachricht hier ebenfalls unbrauchbar: ohne roomId
     * weiss niemand, in welchen Raum sie gehoert, ohne id fehlt das "id:" des Ereignisses.
     * Wuerde eine solche Nachricht stattdessen eine Ausnahme ausloesen, gaebe der Broker
     * sie immer wieder zurueck, und der Hoer-Thread bliebe in einer Schleife haengen.
     */
    private Message parse(byte[] body) {
        Message message;
        try {
            message = objectMapper.readValue(body, Message.class);
        } catch (IOException unlesbar) {
            log.warn("Live-Nachricht ist kein lesbares JSON und wird verworfen: {}", unlesbar.getMessage());
            return null;
        }

        if (message.id() == null) {
            log.warn("Live-Nachricht ohne id wird verworfen");
            return null;
        }
        if (message.roomId() == null) {
            log.warn("Live-Nachricht {} ohne roomId wird verworfen", message.id());
            return null;
        }
        return message;
    }
}
