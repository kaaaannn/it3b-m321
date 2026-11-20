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
 * Prueft das Nachholen nach einem Verbindungsabbruch mit echtem Broker (Plan, Schritt 3,
 * und Abnahme A7): Ein Client, der mit Last-Event-ID zurueckkommt, bekommt genau die
 * verpassten Nachrichten, in Reihenfolge, ohne Doppelte - und danach wieder live.
 *
 * Dieselben Einstellungen wie im LiveMessageListenerTest, damit Spring den schon
 * gestarteten Kontext wiederverwendet, statt einen zweiten hochzufahren.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.shutdown=immediate")
class StreamReplayTest {

    /** Ein eigener Raum, damit Nachrichten aus anderen Testklassen hier nicht mitzaehlen. */
    private static final UUID ROOM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** So viele Nachrichten verpasst der Client, wie in A7 der Spezifikation. */
    private static final int MISSED_COUNT = 50;

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
     * Der Ablauf aus A7: Stream oeffnen, eine Nachricht, Stream schliessen, 50 Nachrichten,
     * neuer Stream mit der ID der ersten. Erwartet werden genau die 50, in der Reihenfolge,
     * in der sie publiziert wurden, keine doppelt; danach kommt eine weitere live an.
     */
    @Test
    void reconnectWithLastEventIdDeliversExactlyTheMissedMessagesInOrderThenLive() throws Exception {
        SseTestClient firstStream = openStream(null);
        UUID firstId = UUID.randomUUID();
        publish(buildJson(firstId, "vor dem Abbruch"));

        // Erst weitermachen, wenn die erste Nachricht sicher angekommen ist. Dann steht sie
        // auch sicher im Zwischenspeicher, denn der wird vor dem Schreiben gefuellt.
        String receivedFirstId = firstStream.nextId(WAIT_FOR_EVENT_MS);
        assertThat(receivedFirstId).isEqualTo(firstId.toString());

        firstStream.close();

        List<String> missedIds = new ArrayList<>();
        for (int i = 0; i < MISSED_COUNT; i++) {
            UUID missedId = UUID.randomUUID();
            missedIds.add(missedId.toString());
            publish(buildJson(missedId, "verpasst " + i));
        }

        SseTestClient secondStream = openStream(firstId.toString());

        // Genau so viele IDs lesen, wie verpasst wurden. Fehlt eine, wartet nextId bis zum
        // Zeitlimit und liefert null - dann stimmt der Vergleich unten nicht.
        List<String> replayedIds = new ArrayList<>();
        for (int i = 0; i < MISSED_COUNT; i++) {
            String replayedId = secondStream.nextId(WAIT_FOR_EVENT_MS);
            replayedIds.add(replayedId);
        }

        // Gleiche Liste heisst: dieselben 50 IDs in derselben Reihenfolge. Weil die 50
        // verschieden sind, ist damit auch keine doppelt.
        assertThat(replayedIds).isEqualTo(missedIds);

        // Nichts darueber hinaus: keine Doppelte, und die erste Nachricht nicht noch einmal.
        String extraId = secondStream.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(extraId).isNull();

        // Danach geht es live weiter.
        UUID liveId = UUID.randomUUID();
        publish(buildJson(liveId, "nach dem Nachholen"));
        String receivedLiveId = secondStream.nextId(WAIT_FOR_EVENT_MS);
        assertThat(receivedLiveId).isEqualTo(liveId.toString());
    }

    /**
     * Eine unbekannte Last-Event-ID holt nichts nach, die Verbindung geht aber trotzdem
     * live weiter (Spezifikation 3.6, Punkt 3).
     */
    @Test
    void reconnectWithUnknownLastEventIdReplaysNothingButStaysLive() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        SseTestClient stream = openStream(unknownId);

        String replayedId = stream.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(replayedId).isNull();

        UUID liveId = UUID.randomUUID();
        publish(buildJson(liveId, "nach unbekannter ID"));
        String receivedLiveId = stream.nextId(WAIT_FOR_EVENT_MS);
        assertThat(receivedLiveId).isEqualTo(liveId.toString());
    }

    /** Oeffnet einen Stream (mit oder ohne Last-Event-ID) und merkt ihn sich zum Schliessen. */
    private SseTestClient openStream(String lastEventId) throws IOException {
        SseTestClient stream = new SseTestClient(port, ROOM_ID, lastEventId);
        openStreams.add(stream);
        return stream;
    }

    /** Legt rohes JSON direkt auf den Exchange, ohne Typ-Header (Spezifikation 2.1). */
    private void publish(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Message amqpMessage = new Message(body, properties);
        rabbitTemplate.send(RabbitConfiguration.EXCHANGE_NAME, "", amqpMessage);
    }

    /** Baut das JSON einer Nachricht mit den fuenf Pflichtfeldern aus dem Vertrag. */
    private String buildJson(UUID id, String text) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"roomId\":\"" + ROOM_ID + "\","
                + "\"sender\":\"lernende1\","
                + "\"text\":\"" + text + "\","
                + "\"sentAt\":\"2026-11-20T08:00:00Z\""
                + "}";
    }
}
