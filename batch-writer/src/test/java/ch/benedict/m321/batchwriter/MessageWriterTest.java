package ch.benedict.m321.batchwriter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft das Schreiben eines Pakets gegen eine ECHTE PostgreSQL-Datenbank.
 *
 * Der zweite und der dritte Test sind der Dublettentest, den die Abgabe verlangt
 * (Szenario S5). Sie beweisen, dass derselbe Schreibvorgang zweimal ausgefuehrt dasselbe
 * Ergebnis hat wie einmal ausgefuehrt. Das nennt man idempotent, und es ist die
 * Voraussetzung dafuer, dass der Broker ein Paket gefahrlos wiederholen darf.
 */
@SpringBootTest
class MessageWriterTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @Autowired
    private MessageWriter messageWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test faengt mit einer leeren Tabelle an, sonst zaehlen wir fremde Zeilen mit. */
    @BeforeEach
    void emptyTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    @Test
    void writesEveryMessageOfTheBatch() {
        List<IncomingMessage> batch = new ArrayList<>();
        batch.add(message(UUID.randomUUID(), "erste Nachricht"));
        batch.add(message(UUID.randomUUID(), "zweite Nachricht"));
        batch.add(message(UUID.randomUUID(), "dritte Nachricht"));

        messageWriter.writeBatch(batch);

        assertThat(countRows()).isEqualTo(3);
    }

    @Test
    void keepsValuesExactlyAsTheySent() {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-09-11T12:36:52.816460Z");
        IncomingMessage original = new IncomingMessage(id, ROOM_ID, "lernende1", "Hallo", sentAt);

        messageWriter.writeBatch(List.of(original));

        String sql = "SELECT sender, text, sent_at FROM message WHERE id = ?";
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(sql, id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("sender")).isEqualTo("lernende1");
        assertThat(rows.get(0).get("text")).isEqualTo("Hallo");

        // Der Zeitpunkt muss derselbe sein, den der chat-service gesetzt hat. Geht hier
        // eine Zeitzone verloren, waere die Reihenfolge im Chat spaeter falsch.
        Instant storedSentAt = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat(storedSentAt).isEqualTo(sentAt);
    }

    @Test
    void writingTheSameMessageTwiceCreatesOneRow() {
        // So sieht es aus, wenn der Broker dasselbe Paket ein zweites Mal zustellt,
        // weil die Bestaetigung nach einem Absturz gefehlt hat.
        UUID id = UUID.randomUUID();
        IncomingMessage once = message(id, "Dublette");

        messageWriter.writeBatch(List.of(once));
        messageWriter.writeBatch(List.of(once));

        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void duplicateInsideOneBatchCreatesOneRow() {
        // Dieselbe Nachricht zweimal im gleichen Paket. Ohne ON CONFLICT wuerde die
        // ganze Anweisung am Primaerschluessel scheitern und das Paket mitnehmen.
        UUID id = UUID.randomUUID();
        IncomingMessage once = message(id, "Dublette im Paket");

        messageWriter.writeBatch(List.of(once, once));

        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void anEmptyBatchIsNoProblem() {
        messageWriter.writeBatch(new ArrayList<>());

        assertThat(countRows()).isZero();
    }

    /** Baut eine Nachricht mit fester Raum-ID und Absender, damit die Tests kurz bleiben. */
    private IncomingMessage message(UUID id, String text) {
        return new IncomingMessage(id, ROOM_ID, "lernende1", text, Instant.parse("2026-09-11T12:00:00Z"));
    }

    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
