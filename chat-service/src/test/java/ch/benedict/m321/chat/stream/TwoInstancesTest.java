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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prueft Szenario S3 ohne laufenden Stack: Haengen zwei Live-Queues am Fanout-Exchange,
 * bekommt JEDE von ihnen JEDE Nachricht, und zwar genau einmal (Plan, Schritt 4).
 *
 * Eine zweite Instanz des chat-service wird dafuer nicht gestartet. Stattdessen bindet der
 * Test eine zweite Queue mit denselben Eigenschaften an den Exchange - genau das, was eine
 * zweite Instanz beim Start tun wuerde. Die erste Live-Queue gehoert der laufenden
 * Anwendung und wird ueber die offenen Streams geprueft, die zweite direkt im Broker.
 *
 * Dieselben Einstellungen wie in den anderen Stream-Tests, damit Spring den schon
 * gestarteten Kontext wiederverwendet.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.shutdown=immediate")
class TwoInstancesTest {

    /** Eigene Raeume, damit Nachrichten aus anderen Testklassen hier nicht mitzaehlen. */
    private static final UUID ROOM_A = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROOM_B = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** Name der Queue, die im Test die zweite Instanz vertritt. */
    private static final String SECOND_QUEUE_NAME = "chat.live.test-zweite-instanz";

    /** So viele Nachrichten werden publiziert. */
    private static final int MESSAGE_COUNT = 20;

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

    /** Damit legt der Test die zweite Queue im Broker an und loescht sie wieder. */
    @Autowired
    private AmqpAdmin amqpAdmin;

    /** Die Live-Queue der laufenden Anwendung. Von ihr uebernimmt die zweite die Eigenschaften. */
    @Autowired
    private Queue liveQueue;

    @Autowired
    private FanoutExchange chatExchange;

    /** Alle Streams, die ein Test geoeffnet hat, damit sie danach sicher zu sind. */
    private final List<SseTestClient> openStreams = new ArrayList<>();

    /**
     * Legt die zweite Queue an und haengt sie an den Exchange, bevor der Test publiziert.
     * Dauerhaft, exklusiv, selbstloeschend und die Argumente (x-max-length, x-message-ttl)
     * werden von der echten Live-Queue uebernommen - so hat sie sicher dieselben
     * Eigenschaften, auch wenn sich die Einstellungen spaeter aendern.
     */
    @BeforeEach
    void bindSecondQueue() {
        Queue secondQueue = new Queue(SECOND_QUEUE_NAME,
                liveQueue.isDurable(),
                liveQueue.isExclusive(),
                liveQueue.isAutoDelete(),
                liveQueue.getArguments());
        amqpAdmin.declareQueue(secondQueue);

        Binding binding = BindingBuilder.bind(secondQueue).to(chatExchange);
        amqpAdmin.declareBinding(binding);
    }

    /**
     * Schliesst alle Streams und loescht die zweite Queue wieder. Sonst bekaeme sie auch
     * die Nachrichten der Tests, die nach diesem laufen.
     */
    @AfterEach
    void cleanUp() throws IOException {
        for (SseTestClient stream : openStreams) {
            stream.close();
        }
        openStreams.clear();

        amqpAdmin.deleteQueue(SECOND_QUEUE_NAME);
    }

    /**
     * Zwei Streams auf Raum A, einer auf Raum B, 20 Nachrichten fuer Raum A. Erwartet:
     * beide A-Streams bekommen alle 20 in Publikationsreihenfolge, der B-Stream keine,
     * und die zweite Queue haelt ebenfalls genau 20 Nachrichten.
     */
    @Test
    void everyLiveQueueReceivesEveryMessageExactlyOnce() throws Exception {
        SseTestClient firstStreamA = openStream(ROOM_A);
        SseTestClient secondStreamA = openStream(ROOM_A);
        SseTestClient streamB = openStream(ROOM_B);

        List<String> publishedIds = new ArrayList<>();
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            UUID messageId = UUID.randomUUID();
            publishedIds.add(messageId.toString());
            publish(buildJson(messageId, ROOM_A, "nachricht " + i));
        }

        // Gleiche Liste heisst: dieselben 20 IDs in derselben Reihenfolge. Weil die
        // publizierten IDs alle verschieden sind, sind es damit auch 20 verschiedene.
        List<String> idsInFirstStream = readIds(firstStreamA, MESSAGE_COUNT);
        assertThat(idsInFirstStream).isEqualTo(publishedIds);

        List<String> idsInSecondStream = readIds(secondStreamA, MESSAGE_COUNT);
        assertThat(idsInSecondStream).isEqualTo(publishedIds);

        // Genau einmal: Nach den 20 kommt in keinem A-Stream ein weiteres Ereignis.
        String extraInFirstStream = firstStreamA.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(extraInFirstStream).isNull();
        String extraInSecondStream = secondStreamA.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(extraInSecondStream).isNull();

        // Raum B hat nichts bekommen, obwohl dieselbe Instanz alle 20 Nachrichten hatte.
        String idInRoomB = streamB.nextId(WAIT_FOR_NOTHING_MS);
        assertThat(idInRoomB).isNull();

        // Die zweite Queue - die "zweite Instanz" - hat ebenfalls jede Nachricht bekommen.
        int messagesInSecondQueue = countMessagesInSecondQueue();
        assertThat(messagesInSecondQueue).isEqualTo(MESSAGE_COUNT);
    }

    /**
     * Liest genau count IDs aus einem Stream. Fehlt eine, liefert nextId nach dem
     * Zeitlimit null - dann stimmt der Vergleich im Test nicht mehr.
     */
    private List<String> readIds(SseTestClient stream, int count) throws InterruptedException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = stream.nextId(WAIT_FOR_EVENT_MS);
            ids.add(id);
        }
        return ids;
    }

    /**
     * Holt die zweite Queue leer und zaehlt dabei. receive liefert null, sobald sie leer ist.
     *
     * Wichtig: receive OHNE Zeitlimit. Mit Zeitlimit meldet sich Spring fuer jede Nachricht
     * kurz als Verbraucher an der Queue an und danach wieder ab. Weil die Queue autoDelete
     * ist, loescht RabbitMQ sie beim Abmelden des letzten Verbrauchers - samt den restlichen
     * Nachrichten (genau das Verhalten aus S4). Ohne Zeitlimit holt receive die Nachricht
     * mit "basic.get", also ohne sich als Verbraucher anzumelden.
     *
     * Ein Zeitlimit braucht es hier nicht: Ein Fanout-Exchange legt jede Nachricht beim
     * Publizieren in alle Queues gleichzeitig. Wenn die Streams alle 20 hatten, liegen
     * die 20 Kopien in der zweiten Queue laengst bereit.
     */
    private int countMessagesInSecondQueue() {
        int count = 0;
        Message received = rabbitTemplate.receive(SECOND_QUEUE_NAME);
        while (received != null) {
            count = count + 1;
            received = rabbitTemplate.receive(SECOND_QUEUE_NAME);
        }
        return count;
    }

    /** Oeffnet einen Stream und merkt ihn sich zum Schliessen nach dem Test. */
    private SseTestClient openStream(UUID roomId) throws IOException {
        SseTestClient stream = new SseTestClient(port, roomId);
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
