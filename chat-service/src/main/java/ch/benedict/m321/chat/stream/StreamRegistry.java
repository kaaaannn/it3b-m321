package ch.benedict.m321.chat.stream;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.benedict.m321.chat.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Verwaltet die offenen Stream-Verbindungen dieser Instanz, getrennt nach Raum.
 *
 * Es gibt diese Klasse, weil zwei Seiten dieselbe Liste brauchen: der Controller legt
 * neue Verbindungen hinein, und der Hoer-Thread der Live-Queue schreibt spaeter in alle
 * Verbindungen eines Raums. Beide greifen aus verschiedenen Threads zu, deshalb sind
 * die Sammlungen thread-sicher gewaehlt (siehe docs/spec-live-zustellung.md, 3.1).
 */
@Component
public class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

    /**
     * Pro Raum eine Liste offener Verbindungen. ConcurrentHashMap, damit zwei Threads
     * gleichzeitig verschiedene Raeume anfassen koennen. CopyOnWriteArrayList, weil wir
     * viel oefter ueber die Liste laufen (jede Nachricht) als sie aendern (nur beim
     * Verbinden und Trennen) - genau dafuer ist diese Liste gebaut.
     */
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByRoom =
            new ConcurrentHashMap<>();

    /**
     * Meldet eine neue Verbindung fuer einen Raum an. Ab jetzt bekommt sie jede
     * Nachricht dieses Raums, die diese Instanz aus ihrer Live-Queue holt.
     */
    public void register(UUID roomId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersForRoom(roomId);
        emitters.add(emitter);
        log.info("Stream-Verbindung fuer Raum {} angemeldet, jetzt {} offen", roomId, emitters.size());
    }

    /**
     * Meldet eine Verbindung wieder ab, zum Beispiel wenn der Client die Verbindung
     * getrennt hat. Eine abgemeldete Verbindung bekommt keine Nachrichten mehr.
     */
    public void remove(UUID roomId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersForRoom(roomId);
        emitters.remove(emitter);
        log.info("Stream-Verbindung fuer Raum {} abgemeldet, noch {} offen", roomId, emitters.size());
    }

    /**
     * Schreibt eine Nachricht als Ereignis in jede offene Verbindung ihres Raums.
     *
     * Das Ereignis traegt die Nachrichten-ID als "id:" und die Nachricht als JSON in
     * "data:" - genau das Format aus der Spezifikation, Abschnitt 2.2. Scheitert das
     * Schreiben bei einer Verbindung, ist der Client weg: die Verbindung wird entfernt,
     * die anderen bekommen die Nachricht trotzdem.
     */
    public void publish(Message message) {
        UUID roomId = message.roomId();
        CopyOnWriteArrayList<SseEmitter> emitters = emittersForRoom(roomId);

        log.debug("Nachricht {} an {} Verbindungen in Raum {} schreiben",
                message.id(), emitters.size(), roomId);

        for (SseEmitter emitter : emitters) {
            boolean delivered = sendTo(emitter, message);
            if (!delivered) {
                remove(roomId, emitter);
            }
        }
    }

    /**
     * Wie viele Verbindungen in einem Raum gerade offen sind. Wird von den Tests
     * gebraucht, um zu sehen, dass An- und Abmelden funktioniert.
     */
    public int openConnections(UUID roomId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersForRoom(roomId);
        return emitters.size();
    }

    /**
     * Schreibt genau ein Ereignis in genau eine Verbindung. Gibt zurueck, ob es geklappt hat.
     *
     * Eine IOException heisst hier: die Leitung zum Client ist weg. Das ist kein Fehler
     * des Dienstes, sondern Alltag bei offenen Verbindungen - deshalb nur ein Logeintrag
     * und kein Weiterwerfen.
     */
    private boolean sendTo(SseEmitter emitter, Message message) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event();
            event.id(message.id().toString());
            event.data(message, MediaType.APPLICATION_JSON);
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException leitungWeg) {
            log.info("Stream-Verbindung in Raum {} ist weg ({}), wird entfernt",
                    message.roomId(), leitungWeg.getClass().getSimpleName());
            emitter.completeWithError(leitungWeg);
            return false;
        }
    }

    /**
     * Holt die Liste eines Raums, legt sie beim ersten Zugriff an. computeIfAbsent ist
     * atomar: auch wenn zwei Threads gleichzeitig denselben neuen Raum anfassen,
     * entsteht nur eine Liste.
     */
    private CopyOnWriteArrayList<SseEmitter> emittersForRoom(UUID roomId) {
        return emittersByRoom.computeIfAbsent(roomId, unbenutzt -> new CopyOnWriteArrayList<>());
    }
}
