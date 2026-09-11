package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testet den Controller allein. @WebMvcTest startet nur die Webschicht, nicht die
 * ganze Anwendung - deshalb braucht dieser Test weder Datenbank noch RabbitMQ.
 * Der Service wird durch eine Attrappe (@MockitoBean) ersetzt, die wir steuern.
 */
@WebMvcTest(MessageController.class)
class MessageControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageService messageService;

    @Test
    void historyReturnsMessagesAsJson() throws Exception {
        // Die Attrappe soll genau eine Nachricht zurueckgeben.
        Message example = new Message(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
                ROOM_ID,
                "lehrperson",
                "Willkommen im Raum Allgemein.",
                Instant.parse("2026-09-04T08:00:00Z"));
        when(messageService.loadHistory(any(), anyInt())).thenReturn(List.of(example));

        mockMvc.perform(get("/api/messages").param("roomId", ROOM_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sender").value("lehrperson"))
                .andExpect(jsonPath("$[0].text").value("Willkommen im Raum Allgemein."));
    }
}
