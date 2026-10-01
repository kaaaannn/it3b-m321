package ch.benedict.m321.batchwriter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.github.dockerjava.api.DockerClient;
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
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die zwei Fehlerfaelle, die die Abgabe ausdruecklich verlangt:
 * den Datenbankausfall (Szenario S7) und die dauerhaft kaputte Nachricht (chat.dlq).
 *
 * Fuer den Ausfall wird der echte Postgres-Container gestoppt und wieder gestartet, nicht
 * nur eine Ausnahme vorgetaeuscht. Nur so sieht man, ob der Dienst den Ausfall wirklich
 * aussitzt, ohne dass ihn jemand neu startet.
 */
@SpringBootTest
class MessageFailureTest {

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
    void emptyTableAndQueues() {
        jdbcTemplate.update("DELETE FROM message");
        drainDeadLetterQueue();
    }

    @Test
    void survivesADatabaseOutageWithoutLosingMessagesAndWithoutRestart() throws InterruptedException {
        DockerClient docker = DockerClientFactory.instance().client();
        String postgresContainerId = TestInfrastructure.POSTGRES.getContainerId();
        int howMany = 30;

        // Die Datenbank wird angehalten, nicht gestoppt. Grund: Testcontainers
        // veroeffentlicht die Datenbank auf einem ZUFAELLIGEN Port des Rechners. Beim
        // Stoppen und Starten wuerde Docker einen neuen Port vergeben, und der Dienst
        // suchte danach an der alten Adresse - das waere ein anderer Fehler als der,
        // den wir pruefen wollen.
        //
        // Angehalten ist ausserdem der haertere Fall: eine gestoppte Datenbank lehnt
        // Verbindungen sofort ab, eine angehaltene antwortet einfach nicht mehr. Der
        // Dienst muss also in eine Zeitgrenze laufen, statt eine klare Absage zu bekommen.
        docker.pauseContainerCmd(postgresContainerId).exec();

        try {
            for (int i = 0; i < howMany; i++) {
                publish(buildJson(UUID.randomUUID(), "waehrend des Ausfalls " + i));
            }

            // So lange bleibt die Datenbank weg. Der Dienst wird in dieser Zeit mehrfach
            // scheitern, warten und dasselbe Paket erneut bekommen.
            Thread.sleep(8000);
        } finally {
            // Unbedingt wieder freigeben, auch wenn oben etwas schiefgeht. Sonst steht
            // die Datenbank fuer alle folgenden Tests still.
            docker.unpauseContainerCmd(postgresContainerId).exec();
        }

        // Niemand hat den batch-writer angefasst: er laeuft in dieser JVM unveraendert
        // weiter. Trotzdem muessen alle Nachrichten ankommen.
        waitUntilRowCountIs(howMany, 60000);

        assertThat(countRows()).isEqualTo(howMany);
    }

    @Test
    void aBrokenMessageGoesToTheDeadLetterQueueAndTheHealthyOnesAreWritten()
            throws InterruptedException {
        // Eine kaputte und eine gesunde Nachricht. Die kaputte darf die gesunde nicht
        // mitnehmen: ohne Einzelweg waere sonst das ganze Paket verloren.
        publish("{ das ist kein JSON");
        publish(buildJson(UUID.randomUUID(), "gesund neben einer kaputten"));

        waitUntilRowCountIs(1, 15000);

        assertThat(countRows()).isEqualTo(1);

        Message inDeadLetterQueue = receiveFromDeadLetterQueue();
        assertThat(inDeadLetterQueue).isNotNull();

        String body = new String(inDeadLetterQueue.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("das ist kein JSON");
    }

    @Test
    void aMessageWithAMissingFieldGoesToTheDeadLetterQueue() throws InterruptedException {
        String withoutText = "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"roomId\":\"" + ROOM_ID + "\","
                + "\"sender\":\"lernende1\","
                + "\"sentAt\":\"2026-09-11T12:36:52Z\""
                + "}";

        publish(withoutText);

        Message inDeadLetterQueue = receiveFromDeadLetterQueue();
        assertThat(inDeadLetterQueue).isNotNull();
        assertThat(countRows()).isZero();
    }

    private void publish(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");

        Message amqpMessage = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(RabbitConfiguration.EXCHANGE_NAME, "", amqpMessage);
    }

    private String buildJson(UUID id, String text) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"roomId\":\"" + ROOM_ID + "\","
                + "\"sender\":\"lernende1\","
                + "\"text\":\"" + text + "\","
                + "\"sentAt\":\"2026-09-11T12:36:52.816460Z\""
                + "}";
    }

    /** Holt eine Nachricht aus chat.dlq und wartet dabei kurz, falls sie noch unterwegs ist. */
    private Message receiveFromDeadLetterQueue() {
        return rabbitTemplate.receive(RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, 15000);
    }

    /** Raeumt die DLQ leer, damit ein Test nicht die Nachricht des vorherigen findet. */
    private void drainDeadLetterQueue() {
        Message leftOver = rabbitTemplate.receive(RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, 100);
        while (leftOver != null) {
            leftOver = rabbitTemplate.receive(RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, 100);
        }
    }

    private void waitUntilRowCountIs(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (countRowsSafely() == expected) {
                return;
            }
            Thread.sleep(500);
        }
    }

    /**
     * Zaehlt die Zeilen, gibt aber minus eins zurueck, solange die Datenbank nicht
     * erreichbar ist. Ohne das wuerde der Zaehlversuch waehrend des Ausfalls selbst
     * eine Ausnahme werfen und den Test abbrechen, statt weiter zu warten.
     */
    private int countRowsSafely() {
        try {
            return countRows();
        } catch (RuntimeException datenbankWegeMoeglich) {
            return -1;
        }
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
