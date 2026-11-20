package ch.benedict.m321.chat.stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Testet den Stream-Endpunkt ohne Broker und ohne Datenbank.
 *
 * Mit @WebMvcTest wird nur die Web-Schicht hochgefahren. Die StreamRegistry ist ein
 * Mockito-Stellvertreter: Hier geht es nur um die Fragen, ob die Antwort als
 * Ereignisstrom ausgeliefert wird und ob eine fehlende roomId sauber abgelehnt wird
 * (Plan, Schritt 1).
 */
@WebMvcTest(StreamController.class)
class StreamControllerTest {

    private static final String ROOM_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamRegistry streamRegistry;

    /** Seit Schritt 3 braucht der Controller auch den Zwischenspeicher, hier ein Stellvertreter. */
    @MockitoBean
    private ReplayBuffer replayBuffer;

    /**
     * Gueltige roomId: Status 200, Content-Type text/event-stream, und die Verbindung
     * wird in der Registry angemeldet. Der Aufruf bleibt absichtlich "asynchron
     * gestartet", weil der Emitter nie geschlossen wird - genau wie im echten Betrieb.
     */
    @Test
    void streamMitGueltigerRoomIdLiefertEventStream() throws Exception {
        mockMvc.perform(get("/stream").param("roomId", ROOM_ID))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        verify(streamRegistry).register(eq(UUID.fromString(ROOM_ID)), any(SseEmitter.class));
    }

    /**
     * Derselbe Endpunkt unter /api/stream, wie es die Spezifikation in Abschnitt 2.1 verlangt.
     */
    @Test
    void streamIstAuchUnterApiPrefixErreichbar() throws Exception {
        mockMvc.perform(get("/api/stream").param("roomId", ROOM_ID))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    /**
     * Ohne roomId gibt es 400 und keine Verbindung wird angemeldet.
     */
    @Test
    void streamOhneRoomIdLiefert400() throws Exception {
        mockMvc.perform(get("/stream"))
                .andExpect(status().isBadRequest());

        verify(streamRegistry, never()).register(any(), any());
    }

    /**
     * Eine roomId, die keine UUID ist, wird ebenfalls mit 400 abgelehnt. Das macht
     * Spring beim Umwandeln des Parameters, bevor unsere Methode laeuft.
     */
    @Test
    void streamMitUngueltigerRoomIdLiefert400() throws Exception {
        mockMvc.perform(get("/stream").param("roomId", "keine-uuid"))
                .andExpect(status().isBadRequest());

        verify(streamRegistry, never()).register(any(), any());
    }
}
