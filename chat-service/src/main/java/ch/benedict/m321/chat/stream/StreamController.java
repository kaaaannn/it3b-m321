package ch.benedict.m321.chat.stream;

import java.io.IOException;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Der Stream-Endpunkt: ein Client oeffnet GET /stream?roomId=... und bekommt ab dann
 * jede Nachricht dieses Raums als Server-Sent Event, solange die Verbindung offen ist.
 *
 * Dieser Controller schreibt selbst keine Nachrichten. Er legt die Verbindung nur in die
 * StreamRegistry; geschrieben wird dort, sobald der Hoer-Thread der Live-Queue eine
 * Nachricht bringt (docs/spec-live-zustellung.md, Abschnitt 2.2 und 3.1).
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

    private final StreamRegistry streamRegistry;

    public StreamController(StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    /**
     * Oeffnet eine Stream-Verbindung fuer einen Raum.
     *
     * Die Antwort hat den Content-Type text/event-stream und bleibt offen. Spring
     * schreibt die Kopfzeilen sofort und haelt die Verbindung ueber den SseEmitter;
     * jedes spaetere emitter.send() wird dem Client als Ereignis nachgeschoben.
     */
    @Operation(
            summary = "Nachrichten eines Raums live empfangen",
            description = "Oeffnet eine Server-Sent-Events-Verbindung. Jede neue Nachricht des Raums "
                        + "kommt als Ereignis mit 'id:' (Nachrichten-ID) und 'data:' (Nachricht als JSON). "
                        + "Zum Ausprobieren von innen: curl -N http://localhost:8080/stream?roomId=...")
    @ApiResponse(responseCode = "200", description = "Verbindung offen, Ereignisse folgen")
    @ApiResponse(responseCode = "400", description = "roomId fehlt oder ist keine UUID")
    @GetMapping(value = {PATH_WITH_API_PREFIX, PATH_WITHOUT_PREFIX},
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam(required = false) UUID roomId) {

        // Pruefen, bevor eine Verbindung angelegt wird. Ohne Raum gibt es nichts zu streamen.
        if (roomId == null) {
            log.warn("Stream-Anfrage ohne roomId abgelehnt");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId fehlt");
        }

        log.info("Stream-Anfrage fuer Raum {}", roomId);

        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        sendOpeningComment(emitter);

        // Wenn die Verbindung endet - egal ob der Client sie schliesst, ein Fehler auftritt
        // oder das Zeitlimit greift - muss sie aus der Registry raus. Sonst wuerde der
        // Hoer-Thread weiter in eine tote Leitung schreiben.
        emitter.onCompletion(() -> streamRegistry.remove(roomId, emitter));
        emitter.onTimeout(() -> streamRegistry.remove(roomId, emitter));
        emitter.onError(fehler -> streamRegistry.remove(roomId, emitter));

        streamRegistry.register(roomId, emitter);
        return emitter;
    }

    /**
     * Schreibt als Erstes eine Kommentarzeile (": verbunden") in den Stream.
     *
     * Seit Spring Framework 6.2 schickt der Server die Kopfzeilen einer SSE-Antwort erst
     * mit dem ersten Schreiben los. Ohne diese Zeile saehe ein Client (oder curl -i) also
     * gar keine Antwort, bis die erste Nachricht im Raum eintrifft. Kommentarzeilen sind
     * im SSE-Format vorgesehen und werden von jedem Client stillschweigend ueberlesen.
     *
     * Das Schreiben vor der Rueckgabe ist erlaubt: Der Emitter merkt es sich und schreibt
     * es, sobald Spring die Antwort uebernommen hat.
     */
    private void sendOpeningComment(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("verbunden"));
        } catch (IOException leitungWeg) {
            log.info("Stream-Verbindung schon beim Oeffnen weg ({})", leitungWeg.getMessage());
            emitter.completeWithError(leitungWeg);
        }
    }
}
