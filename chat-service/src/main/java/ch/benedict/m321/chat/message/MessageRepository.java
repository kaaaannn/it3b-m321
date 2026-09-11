package ch.benedict.m321.chat.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Liest Nachrichten aus der Datenbank. Bewusst ohne ORM: das SQL steht im Klartext
 * da und jede Spalte wird von Hand in ein Feld uebertragen - man kann es Zeile fuer
 * Zeile vorlesen.
 *
 * Schreiben gibt es hier absichtlich nicht. In die Nachrichtentabelle schreibt
 * ausschliesslich der batch-service (siehe PLANUNG.md, Abschnitt 2.3).
 */
@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * Spring reicht den JdbcTemplate hier herein (Konstruktor-Injektion). Wir bauen
     * ihn nicht selbst - dann koennte man ihn im Test nicht austauschen.
     */
    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Holt die letzten Nachrichten eines Raums, neueste zuerst.
     * Der Index aus 01-schema.sql passt genau auf diese Abfrage.
     */
    public List<Message> findLatest(UUID roomId, int limit) {
        String sql = "SELECT id, room_id, sender, text, sent_at "
                   + "FROM message "
                   + "WHERE room_id = ? "
                   + "ORDER BY sent_at DESC "
                   + "LIMIT ?";

        log.debug("Lese die letzten {} Nachrichten aus Raum {}", limit, roomId);

        // Die Fragezeichen werden vom Treiber gefuellt. Niemals Werte in den
        // SQL-String kleben - das waere eine Einladung fuer SQL-Injection.
        List<Message> found = jdbcTemplate.query(sql, this::mapRow, roomId, limit);

        log.debug("{} Nachrichten aus Raum {} gelesen", found.size(), roomId);
        return found;
    }

    /**
     * Uebertraegt eine Ergebniszeile der Datenbank in ein Message-Objekt.
     * Diese Methode wird oben pro gefundener Zeile einmal aufgerufen.
     */
    private Message mapRow(ResultSet row, int rowNumber) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);
        String sender = row.getString("sender");
        String text = row.getString("text");
        Timestamp timestamp = row.getTimestamp("sent_at");
        Instant sentAt = timestamp.toInstant();
        return new Message(id, roomId, sender, text, sentAt);
    }
}
