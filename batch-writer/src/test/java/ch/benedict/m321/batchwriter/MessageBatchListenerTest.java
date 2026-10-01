package ch.benedict.m321.batchwriter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft den ganzen Weg mit echter Queue und echter Datenbank: publizieren, buendeln,
 * schreiben, bestaetigen.
 *
 * Publiziert wird absichtlich als nackter JSON-Rumpf mit nur einem Header,
 * content_type application/json. Genau so legt Szenario S5 der Bewertung eine Nachricht
 * in die Queue, und genau so soll der Dienst sie auch verstehen.
 */
@SpringBootTest
class MessageBatchListenerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    @Test
    void writesEveryPublishedMessage() throws InterruptedException {
        int howMany = 1000;

        for (int i = 0; i < howMany; i++) {
            publish(buildJson(UUID.randomUUID(), "Nachricht Nummer " + i));
        }

        // Szenario S3 der Bewertung gibt sechzig Sekunden. Wir pruefen strenger.
        waitUntilRowCountIs(howMany, 30000);

        assertThat(countRows()).isEqualTo(howMany);
    }

    @Test
    void writesAnIncompleteBatchAfterTheTimeout() throws InterruptedException {
        // Drei Nachrichten sind weit unter der Paketgroesse. Ohne Zeitlimit blieben sie
        // liegen, bis irgendwann fuenfhundert zusammenkommen - also moeglicherweise ewig.
        publish(buildJson(UUID.randomUUID(), "einzeln eins"));
        publish(buildJson(UUID.randomUUID(), "einzeln zwei"));
        publish(buildJson(UUID.randomUUID(), "einzeln drei"));

        waitUntilRowCountIs(3, 10000);

        assertThat(countRows()).isEqualTo(3);
    }

    @Test
    void theSameMessagePublishedTwiceCreatesOneRow() throws InterruptedException {
        // Das ist Szenario S5 vollstaendig durchgespielt: zweimal dieselbe Nachricht
        // ueber die Queue, nur mit content_type als Header.
        UUID id = UUID.randomUUID();
        String json = buildJson(id, "Dublette durch die Queue");

        publish(json);
        publish(json);

        waitUntilRowCountIs(1, 10000);

        assertThat(countRows()).isEqualTo(1);
    }

    /**
     * Legt eine Nachricht auf den Fanout-Exchange, so wie der chat-service es tut:
     * leerer Routing-Key, weil ein Fanout-Exchange ihn nicht anschaut.
     */
    private void publish(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");

        Message amqpMessage = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(RabbitConfiguration.EXCHANGE_NAME, "", amqpMessage);
    }

    /** Baut den JSON-Rumpf genau nach dem Vertrag aus Abschnitt 2.2 der Spezifikation. */
    private String buildJson(UUID id, String text) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"roomId\":\"" + ROOM_ID + "\","
                + "\"sender\":\"lernende1\","
                + "\"text\":\"" + text + "\","
                + "\"sentAt\":\"2026-09-11T12:36:52.816460Z\""
                + "}";
    }

    /**
     * Wartet, bis die erwartete Zahl in der Tabelle steht, hoechstens aber so lange wie
     * angegeben. Es gibt diese Methode, weil der Weg ueber die Queue nicht sofort fertig
     * ist: zwischen Senden und Schreiben liegen Buendelung und Zeitlimit.
     */
    private void waitUntilRowCountIs(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (countRows() == expected) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
