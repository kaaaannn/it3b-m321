package ch.benedict.m321.batchwriter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt ein ganzes Paket Nachrichten mit EINER Anweisung in die Tabelle message.
 *
 * Warum gebuendelt und nicht einzeln: jede einzelne Anweisung kostet einen Weg zur
 * Datenbank, ein Parsen und einen Commit. Szenario S4 der Bewertung macht daraus eine
 * harte Grenze - tausend Nachrichten durften hoechstens hundert Transaktionen ausloesen.
 * Mit Paketen von fuenfhundert sind es zwei bis drei.
 */
@Component
public class MessageWriter {

    private static final Logger log = LoggerFactory.getLogger(MessageWriter.class);

    /**
     * ON CONFLICT (id) DO NOTHING ist der Kern der Dublettenfreiheit: die id ist
     * Primaerschluessel und kommt vom chat-service. Kennt die Datenbank sie schon,
     * laesst sie die Zeile einfach weg, ohne Fehler. Deshalb darf der Broker dasselbe
     * Paket gefahrlos ein zweites Mal zustellen.
     */
    private static final String INSERT_SQL =
            "INSERT INTO message (id, room_id, sender, text, sent_at) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public MessageWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Schreibt alle Nachrichten des Pakets in einer einzigen Transaktion.
     *
     * @Transactional heisst: entweder sind am Ende ALLE Zeilen drin oder keine. Das ist
     * die Voraussetzung dafuer, dass der Listener erst NACH dem Commit bestaetigen darf -
     * sonst waere eine halb geschriebene Nachricht moeglich, die als erledigt gilt.
     */
    @Transactional
    public void writeBatch(List<IncomingMessage> messages) {
        if (messages.isEmpty()) {
            // Kann vorkommen, wenn ein Paket nur kaputte Nachrichten enthielt.
            log.debug("Leeres Paket, nichts zu schreiben");
            return;
        }

        List<Object[]> rows = buildRows(messages);

        log.debug("Schreibe Paket mit {} Nachrichten", rows.size());

        // batchUpdate schickt alle Zeilen in einem Rutsch. Die Rueckgabewerte werten wir
        // absichtlich NICHT aus: steht reWriteBatchedInserts=true in der JDBC-Adresse,
        // darf der Treiber die Anweisungen zusammenfassen und meldet dann keine Zahlen
        // mehr zurueck. Wie viele Zeilen wirklich neu sind, zaehlt bei Bedarf eine Abfrage.
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);

        log.info("Paket mit {} Nachrichten geschrieben", rows.size());
    }

    /**
     * Legt jede Nachricht als Zeile von Werten ab, in der Reihenfolge der Fragezeichen
     * im SQL oben. Die Werte gehen als Parameter an den Treiber und werden nie in den
     * SQL-Text geklebt - das waere eine Einladung fuer SQL-Injection.
     */
    private List<Object[]> buildRows(List<IncomingMessage> messages) {
        List<Object[]> rows = new ArrayList<>();

        for (IncomingMessage message : messages) {
            Object[] row = new Object[5];
            row[0] = message.id();
            row[1] = message.roomId();
            row[2] = message.sender();
            row[3] = message.text();
            row[4] = toUtc(message.sentAt());
            rows.add(row);
        }

        return rows;
    }

    /**
     * Macht aus dem Zeitpunkt einen Wert mit ausdruecklicher Zeitzone UTC.
     *
     * Das ist wichtig: gibt man den Zeitpunkt ohne Zone weiter, nimmt der Treiber die
     * Zeitzone des Rechners, auf dem der Dienst laeuft. Dann stuende dieselbe Nachricht
     * je nach Rechner mit einer anderen Uhrzeit in der Datenbank.
     */
    private OffsetDateTime toUtc(java.time.Instant sentAt) {
        return OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC);
    }
}
