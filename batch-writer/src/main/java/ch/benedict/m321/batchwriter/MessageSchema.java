package ch.benedict.m321.batchwriter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Legt die Tabelle message und ihren Index an, falls sie fehlen.
 *
 * Es gibt diese Klasse, weil die Tabelle message dem batch-writer gehoert: er ist ihr
 * einziger Schreiber. Das Init-Skript des Postgres-Containers kann sie nicht
 * zuverlaessig anlegen, denn es laeuft NUR beim allerersten Start auf ein leeres
 * Volume. Liegt schon ein Volume von frueher, fehlte die Tabelle stillschweigend.
 */
@Component
public class MessageSchema {

    private static final Logger log = LoggerFactory.getLogger(MessageSchema.class);

    private final JdbcTemplate jdbcTemplate;

    public MessageSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Laeuft einmal, nachdem Spring dieses Objekt gebaut hat, und damit bevor der
     * Hoer-Thread die erste Nachricht aus der Queue holt. So ist die Tabelle sicher
     * da, wenn das erste Paket geschrieben wird.
     */
    @PostConstruct
    public void createIfMissing() {
        log.info("Pruefe Tabelle message und lege sie bei Bedarf an");

        // "IF NOT EXISTS" macht die Anweisung wiederholbar: beim zweiten Start passiert
        // nichts, und es gibt keinen Fehler. Genau das brauchen wir bei jedem Neustart.
        String createTable = "CREATE TABLE IF NOT EXISTS message ("
                + "id UUID PRIMARY KEY, "
                // Die id kommt vom chat-service, NICHT von der Datenbank. Nur deshalb
                // kann ein Paket gefahrlos wiederholt werden, ohne Dubletten zu erzeugen.
                + "room_id UUID NOT NULL, "
                // Absichtlich OHNE Fremdschluessel auf room: eine Nachricht kann vor
                // ihrem Raum ankommen und wuerde sonst grundlos abgelehnt.
                + "sender VARCHAR(100) NOT NULL, "
                + "text TEXT NOT NULL, "
                // Zeitpunkt des SENDENS, gesetzt vom chat-service. Kein DEFAULT now():
                // sonst haetten alle Nachrichten eines Pakets dieselbe Zeit.
                + "sent_at TIMESTAMPTZ NOT NULL)";
        jdbcTemplate.execute(createTable);

        // Der Verlauf wird immer pro Raum und nach Zeit gelesen. Genau dafuer ist dieser
        // Index da. Weitere Indexe gibt es nicht: jeder kostet bei jedem Schreibvorgang
        // Zeit, und Schreiben ist in diesem Dienst der Engpass.
        String createIndex = "CREATE INDEX IF NOT EXISTS idx_message_room_time "
                + "ON message (room_id, sent_at DESC)";
        jdbcTemplate.execute(createIndex);

        log.info("Tabelle message und Index idx_message_room_time sind bereit");
    }
}
