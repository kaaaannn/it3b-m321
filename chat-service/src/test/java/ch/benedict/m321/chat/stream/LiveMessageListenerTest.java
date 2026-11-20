package ch.benedict.m321.chat.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ch.benedict.m321.chat.TestInfrastructure;
import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prueft den ganzen Live-Weg mit echtem Broker: Nachricht auf den Exchange ->
 * Live-Queue dieser Instanz -> LiveMessageListener -> offener Stream (Plan, Schritt 2).
 *
 * Die Anwendung laeuft dafuer auf einem echten, zufaelligen Port (RANDOM_PORT), damit
 * der SseTestClient eine richtige HTTP-Verbindung oeffnen und den Stream lesen kann.
 *
 * "server.shutdown=immediate" gilt nur fuer diesen Test: Spring Boot faehrt den Webserver
 * sonst "sanft" herunter und wartet bis zu 30 Sekunden, bis alle offenen Anfragen fertig
 * sind. Ein Stream ist aber nie fertig - der Testlauf haengte am Ende 30 Sekunden.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.shutdown=immediate")
class LiveMessageListenerTest {

    private static final UUID ROOM_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** So lange wartet der Test hoechstens auf ein Ereignis, das kommen MUSS. */
    private static final long WAIT_FOR_EVENT_MS = 10000;

    /** So lange schaut der Test, ob ein Ereignis kommt, das NICHT kommen darf. */
    private static final long WAIT_FOR_NOTHING_MS = 2000;

    /** Gibt Spring die Adressen der Testcontainer, bevor der Kontext startet. */
    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** Alle Streams, die ein Test geoeffnet hat, damit sie danach sicher zu sind. */
    private final List<SseTestClient> openStreams = new ArrayList<>();

    /** Schliesst nach jedem Test alle Streams, sonst blieben sie bis zum Ende der JVM offen. */
    @AfterEach
    void closeStreams() throws IOException {
        for (SseTestClient stream : openStreams) {
            stream.close();
        }
        openStreams.clear();
    }

    /**
     * Der Kern von Schritt 2: Eine Nachricht fuer Raum A erscheint genau einmal im
     * Stream von Raum A, mit ihrer ID als "id:". Der Stream von Raum B bekommt nichts.
     */
    @Test
    void messageOnExchangeReachesTheStreamOfItsRoomExactlyOnce() throws Exception {
        SseTestClient streamA = openStream(ROOM_A);
        SseTestClient streamB = openStream(ROOM_B);

        UUID messageId = UUID.randomUUID();
        publish(buildJson(messageId, ROOM_A, "hallo live"));

        String firstId = streamA.nextId(WAIT_FOR_EVENT_MS);
        assertThat(firstId).isEqualTo(messageId.toString());

        // Genau ein Ereignis: Nach dem ersten darf keines mit derselben Nachricht folgen.
        String secondId = streamA.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(secondId).isNull();

        String idInRoomB = streamB.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(idInRoomB).isNull();
    }

    /**
     * Eine unlesbare Nachricht wird verworfen und blockiert die Queue nicht: Die gueltige
     * Nachricht direkt dahinter kommt trotzdem an.
     */
    @Test
    void unreadableMessageIsDroppedAndTheNextOneStillArrives() throws Exception {
        SseTestClient streamA = openStream(ROOM_A);

        publish("{ das ist kein JSON");
        UUID messageId = UUID.randomUUID();
        publish(buildJson(messageId, ROOM_A, "nach einer kaputten"));

        String firstId = streamA.nextId(WAIT_FOR_EVENT_MS);
        assertThat(firstId).isEqualTo(messageId.toString());
    }

    /** Oeffnet einen Stream und merkt ihn sich zum Schliessen nach dem Test. */
    private SseTestClient openStream(UUID roomId) throws IOException {
        SseTestClient stream = new SseTestClient(port, roomId);
        openStreams.add(stream);
        return stream;
    }

    /**
     * Legt rohes JSON direkt auf den Exchange, nur mit content_type und ohne Typ-Header -
     * so, wie es der Vertrag in der Spezifikation (Abschnitt 2.1) beschreibt.
     */
    private void publish(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Message amqpMessage = new Message(body, properties);
        rabbitTemplate.send(RabbitConfiguration.EXCHANGE_NAME, "", amqpMessage);
    }

    /** Baut das JSON einer Nachricht mit den fuenf Pflichtfeldern aus dem Vertrag. */
    private String buildJson(UUID id, UUID roomId, String text) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"roomId\":\"" + roomId + "\","
                + "\"sender\":\"lernende1\","
                + "\"text\":\"" + text + "\","
                + "\"sentAt\":\"2026-11-20T08:00:00Z\""
                + "}";
    }
}
