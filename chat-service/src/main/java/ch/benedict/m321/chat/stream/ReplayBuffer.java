package ch.benedict.m321.chat.stream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Der Zwischenspeicher fuer das Nachholen: pro Raum die letzten Nachrichten, die diese
 * Instanz aus ihrer Live-Queue bekommen hat (docs/spec-live-zustellung.md, Abschnitt 3.6).
 *
 * Es gibt ihn, damit ein Client nach einem Verbindungsabbruch genau das bekommt, was ihm
 * fehlt - ohne Datenbank. Darum geht das Nachholen auch, wenn postgres und der
 * batch-writer gestoppt sind (Szenario S5).
 *
 * Pro Raum ist der Speicher ein Ringpuffer: Ist er voll, faellt die aelteste Nachricht
 * hinten raus. So bleibt der Arbeitsspeicher begrenzt, egal wie lange die Instanz laeuft.
 *
 * Alle Methoden sind "synchronized": Der Hoer-Thread der Live-Queue schreibt hinein,
 * waehrend die Web-Threads der Stream-Anfragen daraus lesen. Ohne Sperre koennte ein
 * Web-Thread die Liste lesen, waehrend der Hoer-Thread sie gerade veraendert.
 */
@Component
public class ReplayBuffer {

    /** Hoechstens so viele Nachrichten pro Raum (Einstellung chat-service.replay-buffer-size). */
    private final int maxMessagesPerRoom;

    /**
     * Pro Raum eine Warteschlange. ArrayDeque, weil wir hinten anhaengen und vorne
     * wegnehmen - genau das kann diese Klasse schnell. Eine einfache HashMap reicht, weil
     * jeder Zugriff ohnehin ueber die synchronized-Methoden laeuft.
     */
    private final HashMap<UUID, ArrayDeque<Message>> messagesByRoom = new HashMap<>();

    /** Die Groesse kommt aus der Umgebung (LIVE_REPLAY_BUFFER_SIZE, siehe .env.example). */
    public ReplayBuffer(@Value("${chat-service.replay-buffer-size}") int maxMessagesPerRoom) {
        this.maxMessagesPerRoom = maxMessagesPerRoom;
    }

    /**
     * Legt eine Nachricht hinten in den Puffer ihres Raums. Ist der Puffer danach zu gross,
     * wird die aelteste Nachricht vorne entfernt.
     */
    public synchronized void add(Message message) {
        ArrayDeque<Message> messages = messagesForRoom(message.roomId());
        messages.addLast(message);

        if (messages.size() > maxMessagesPerRoom) {
            messages.removeFirst();
        }
    }

    /**
     * Liefert alle Nachrichten eines Raums, die NACH der Nachricht mit lastEventId kamen,
     * in Ankunftsreihenfolge. Die Nachricht mit lastEventId selbst ist nicht dabei - die
     * hat der Client schon. Darum gibt es keine Doppelten.
     *
     * Steht die ID nicht im Puffer (zu alt, schon verdraengt, oder nie gesehen), ist die
     * Liste leer und das Ergebnis traegt das Kennzeichen "unbekannt".
     */
    public synchronized ReplayResult after(UUID roomId, String lastEventId) {
        ArrayDeque<Message> messages = messagesForRoom(roomId);
        List<Message> missedMessages = new ArrayList<>();
        boolean lastEventIdFound = false;

        // Von der aeltesten zur neuesten durchgehen. Alles, was nach der gesuchten ID
        // kommt, ist verpasst. Wird die ID nie gefunden, bleibt die Liste leer.
        for (Message message : messages) {
            if (lastEventIdFound) {
                missedMessages.add(message);
            }
            String messageId = message.id().toString();
            if (messageId.equals(lastEventId)) {
                lastEventIdFound = true;
            }
        }

        return new ReplayResult(lastEventIdFound, missedMessages);
    }

    /**
     * Holt den Puffer eines Raums und legt ihn beim ersten Zugriff an. Wird nur aus den
     * synchronized-Methoden oben aufgerufen, deshalb braucht es hier keine eigene Sperre.
     */
    private ArrayDeque<Message> messagesForRoom(UUID roomId) {
        ArrayDeque<Message> messages = messagesByRoom.get(roomId);
        if (messages == null) {
            messages = new ArrayDeque<>();
            messagesByRoom.put(roomId, messages);
        }
        return messages;
    }
}
