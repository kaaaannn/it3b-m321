package ch.benedict.m321.chat.stream;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.message.Message;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Der Stream-Endpunkt: ein Client oeffnet GET /stream?roomId=... und bekommt ab dann
 * jede Nachricht dieses Raums als Server-Sent Event, solange die Verbindung offen ist.
 *
 * Neue Nachrichten schreibt nicht dieser Controller, sondern die StreamRegistry, sobald
 * der Hoer-Thread der Live-Queue eine bringt. Dieser Controller schreibt nur beim
 * Oeffnen: die Begruessung und - wenn der Client mit Last-Event-ID zurueckkommt - die
 * Nachrichten, die er verpasst hat (docs/spec-live-zustellung.md, Abschnitte 2.2 und 3.6).
 */
@RestController
@Tag(name = "Stream", description = "Nachrichten eines Raums live empfangen (Server-Sent Events)")
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /** Beide Pfade fuehren auf dieselbe Methode - aus demselben Grund wie bei /messages. */
    private static final String PATH_WITH_API_PREFIX = "/api/stream";
    private static final String PATH_WITHOUT_PREFIX = "/stream";

    /**
     * Zeitlimit der Verbindung in Millisekunden. Der Wert ist praktisch unendlich:
     * eine Stream-Verbindung soll offen bleiben, bis der Client sie schliesst, nicht
     * nach dem Standard von dreissig Sekunden vom Server beendet werden.
     */
    private static final long NO_TIMEOUT = Long.MAX_VALUE;

    /**
     * Name der Kopfzeile, mit der ein Client nach einem Abbruch sagt, welches Ereignis er
     * zuletzt bekommen hat. Ein Browser (EventSource) schickt sie von selbst mit.
     */
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    private final StreamRegistry streamRegistry;
    private final ReplayBuffer replayBuffer;

    /**
     * Spring reicht die Registry der offenen Verbindungen herein (dort meldet der Controller
     * jeden neuen Stream an) und den Zwischenspeicher, aus dem er beim Nachholen liest.
     */
    public StreamController(StreamRegistry streamRegistry, ReplayBuffer replayBuffer) {
        this.streamRegistry = streamRegistry;
        this.replayBuffer = replayBuffer;
    }

    /**
     * Oeffnet eine Stream-Verbindung fuer einen Raum.
     *
     * Die Antwort hat den Content-Type text/event-stream und bleibt offen. Kommt der
     * Client mit Last-Event-ID, bekommt er zuerst die verpassten Nachrichten, danach live.
     */
    @Operation(
            summary = "Nachrichten eines Raums live empfangen",
            description = "Oeffnet eine Server-Sent-Events-Verbindung. Jede neue Nachricht des Raums "
                        + "kommt als Ereignis mit 'id:' (Nachrichten-ID) und 'data:' (Nachricht als JSON). "
                        + "Mit der Kopfzeile Last-Event-ID kommen zuerst die Nachrichten nach dieser ID. "
                        + "Zum Ausprobieren von innen: curl -N http://localhost:8080/stream?roomId=...")
    @ApiResponse(responseCode = "200", description = "Verbindung offen, Ereignisse folgen")
    @ApiResponse(responseCode = "400", description = "roomId fehlt oder ist keine UUID")
    @GetMapping(value = {PATH_WITH_API_PREFIX, PATH_WITHOUT_PREFIX},
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam(required = false) UUID roomId,
            @Parameter(description = "ID des letzten empfangenen Ereignisses, nur bei einer Neuverbindung")
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {

        // Pruefen, bevor eine Verbindung angelegt wird. Ohne Raum gibt es nichts zu streamen.
        if (roomId == null) {
            log.warn("Stream-Anfrage ohne roomId abgelehnt");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId fehlt");
        }

        log.info("Stream-Anfrage fuer Raum {}, Last-Event-ID {}", roomId, lastEventId);

        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        sendComment(emitter, "verbunden");

        // Wenn die Verbindung endet - egal ob der Client sie schliesst, ein Fehler auftritt
        // oder das Zeitlimit greift - muss sie aus der Registry raus. Sonst wuerde der
        // Hoer-Thread weiter in eine tote Leitung schreiben.
        emitter.onCompletion(() -> streamRegistry.remove(roomId, emitter));
        emitter.onTimeout(() -> streamRegistry.remove(roomId, emitter));
        emitter.onError(fehler -> streamRegistry.remove(roomId, emitter));

        // Nachholen und Anmelden unter derselben Sperre wie im LiveMessageListener. Waehrend
        // wir hier sind, kann der Hoer-Thread keine neue Nachricht verteilen. Sonst koennte
        // eine Nachricht nach dem Nachholen, aber vor dem Anmelden ankommen und waere fuer
        // diesen Client verloren (Spezifikation 3.6: keine fehlt, keine doppelt).
        synchronized (replayBuffer) {
            boolean isReconnect = lastEventId != null && !lastEventId.isBlank();
            if (isReconnect) {
                replayMissedMessages(roomId, lastEventId, emitter);
            }
            streamRegistry.register(roomId, emitter);
        }
        return emitter;
    }

    /**
     * Schreibt einem zurueckkehrenden Client alle Nachrichten nach seiner Last-Event-ID,
     * in Reihenfolge. Kennt der Zwischenspeicher die ID nicht, gibt es stattdessen den
     * Kommentar ": last-event-id unbekannt" - der Client holt den Rest dann ueber
     * GET /api/messages aus der Datenbank.
     */
    private void replayMissedMessages(UUID roomId, String lastEventId, SseEmitter emitter) {
        ReplayResult result = replayBuffer.after(roomId, lastEventId);

        if (!result.lastEventIdKnown()) {
            log.info("Last-Event-ID {} in Raum {} unbekannt, nichts nachzuholen", lastEventId, roomId);
            sendComment(emitter, "last-event-id unbekannt");
            return;
        }

        List<Message> missedMessages = result.missedMessages();
        log.info("Hole {} Nachrichten in Raum {} nach {} nach", missedMessages.size(), roomId, lastEventId);

        for (Message message : missedMessages) {
            boolean delivered = streamRegistry.sendTo(emitter, message);
            // Ist die Leitung schon wieder weg, lohnt sich der Rest nicht mehr.
            if (!delivered) {
                return;
            }
        }
    }

    /**
     * Schreibt eine Kommentarzeile (": text") in den Stream. Kommentarzeilen sind im
     * SSE-Format vorgesehen und werden von jedem Client stillschweigend ueberlesen.
     *
     * Gebraucht wird das zweimal: Als Erstes schreibt der Controller ": verbunden". Seit
     * Spring Framework 6.2 schickt der Server die Kopfzeilen einer SSE-Antwort erst mit
     * dem ersten Schreiben los - ohne diese Zeile saehe ein Client (oder curl -i) gar keine
     * Antwort, bis die erste Nachricht kommt. Und beim Nachholen meldet ein Kommentar,
     * dass die Last-Event-ID unbekannt ist.
     *
     * Das Schreiben vor der Rueckgabe ist erlaubt: Der Emitter merkt es sich und schreibt
     * es, sobald Spring die Antwort uebernommen hat.
     */
    private void sendComment(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().comment(text));
        } catch (IOException leitungWeg) {
            log.info("Stream-Verbindung schon beim Oeffnen weg ({})", leitungWeg.getMessage());
            emitter.completeWithError(leitungWeg);
        }
    }
}
