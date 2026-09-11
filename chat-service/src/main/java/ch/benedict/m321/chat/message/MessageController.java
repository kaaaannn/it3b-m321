package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die REST-Schnittstelle fuer Nachrichten. Die Annotationen aus io.swagger.v3
 * beschreiben jeden Endpunkt - daraus baut springdoc die Swagger-Oberflaeche.
 * Was hier nicht beschrieben ist, taucht in der Dokumentation auch nicht auf.
 */
@RestController
@RequestMapping("/api/messages")
@Tag(name = "Nachrichten", description = "Nachrichten senden und den Verlauf eines Raums lesen")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    /** Obergrenze fuer "limit". Schuetzt die Datenbank vor einer Abfrage ueber Millionen Zeilen. */
    private static final int MAX_LIMIT = 100;

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Liefert die letzten Nachrichten eines Raums, neueste zuerst.
     * Gelesen wird direkt aus der Datenbank - dieser Weg laeuft voellig getrennt
     * vom Senden ueber RabbitMQ.
     */
    @Operation(
            summary = "Verlauf eines Raums lesen",
            description = "Gibt die letzten Nachrichten eines Raums zurueck, neueste zuerst. "
                        + "Demo-Raum zum Ausprobieren: 11111111-1111-1111-1111-111111111111")
    @ApiResponse(responseCode = "200", description = "Verlauf, moeglicherweise leer")
    @ApiResponse(responseCode = "400", description = "limit ist kleiner als 1 oder groesser als 100")
    @GetMapping
    public List<Message> history(
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam UUID roomId,

            @Parameter(description = "Wie viele Nachrichten hoechstens (1 bis 100)", example = "50")
            @RequestParam(defaultValue = "50") int limit) {

        log.info("Verlauf abgerufen: Raum {}, limit {}", roomId, limit);

        // Grenzen pruefen, bevor die Zahl in die SQL-Abfrage geht.
        if (limit < 1 || limit > MAX_LIMIT) {
            log.warn("Ungueltiges limit {} fuer Raum {} - Anfrage abgelehnt", limit, roomId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }

        List<Message> history = messageService.loadHistory(roomId, limit);
        log.info("Verlauf geliefert: Raum {}, {} Nachrichten", roomId, history.size());
        return history;
    }

    /**
     * Nimmt eine Nachricht entgegen und gibt sie an RabbitMQ weiter.
     *
     * Die Antwort ist 202 Accepted und nicht 201 Created: wir haben die Nachricht
     * angenommen und weitergegeben, gespeichert ist sie in diesem Moment noch nicht.
     * 201 wuerde etwas versprechen, was noch nicht stimmt.
     */
    @Operation(
            summary = "Nachricht senden",
            description = "Nimmt eine Nachricht an und publiziert sie auf den Fanout-Exchange "
                        + "'chat.messages'. Die Antwort kommt sofort. Gespeichert wird die "
                        + "Nachricht kurz danach vom batch-service - sie erscheint also erst "
                        + "mit kleiner Verzoegerung im Verlauf.")
    @ApiResponse(responseCode = "202", description = "Nachricht angenommen und publiziert")
    @ApiResponse(responseCode = "400", description = "roomId fehlt oder der Text ist leer")
    @PostMapping
    public ResponseEntity<Message> send(@RequestBody NewMessage incoming) {

        log.info("Sendeanfrage erhalten: Raum {}, Absender {}", incoming.roomId(), incoming.sender());

        // Eingaben pruefen, bevor irgendetwas den Dienst verlaesst.
        if (incoming.roomId() == null) {
            log.warn("Sendeanfrage ohne roomId abgelehnt");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId fehlt");
        }
        if (incoming.sender() == null || incoming.sender().isBlank()) {
            log.warn("Sendeanfrage ohne sender fuer Raum {} abgelehnt", incoming.roomId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sender fehlt");
        }
        if (incoming.text() == null || incoming.text().isBlank()) {
            log.warn("Sendeanfrage mit leerem Text fuer Raum {} abgelehnt", incoming.roomId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text darf nicht leer sein");
        }

        Message published = messageService.sendMessage(incoming);

        log.info("Sendeanfrage beantwortet: Nachricht {} angenommen", published.id());
        return ResponseEntity.accepted().body(published);
    }
}
