package ch.benedict.m321.batchwriter;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft, dass der Dienst die Tabelle message und ihren Index beim Start selbst anlegt.
 * Es gibt diesen Test, weil die Tabelle dem batch-writer gehoert: er ist ihr einziger
 * Schreiber, also darf er sich nicht darauf verlassen, dass jemand anders sie anlegt
 * (siehe docs/spec-batch-writer.md, Abschnitt 4.2).
 */
@SpringBootTest
class MessageSchemaTest {

    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MessageSchema messageSchema;

    @Test
    void tableExistsAfterStartup() {
        String sql = "SELECT count(*) FROM information_schema.tables WHERE table_name = 'message'";
        Integer found = jdbcTemplate.queryForObject(sql, Integer.class);
        assertThat(found).isEqualTo(1);
    }

    @Test
    void tableHasTheColumnsFromTheSpecification() {
        String sql = "SELECT column_name FROM information_schema.columns "
                   + "WHERE table_name = 'message' ORDER BY column_name";
        List<String> columns = jdbcTemplate.queryForList(sql, String.class);
        assertThat(columns).containsExactly("id", "room_id", "sender", "sent_at", "text");
    }

    @Test
    void indexForTheHistoryQueryExists() {
        String sql = "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_message_room_time'";
        Integer found = jdbcTemplate.queryForObject(sql, Integer.class);
        assertThat(found).isEqualTo(1);
    }

    @Test
    void creatingTheSchemaTwiceDoesNoHarm() {
        // Der Dienst startet im Betrieb oft neu, und jedes Mal laeuft dieselbe Anweisung.
        // Dieser Aufruf ist der zweite Durchlauf: er darf keinen Fehler werfen.
        messageSchema.createIfMissing();

        String sql = "SELECT count(*) FROM information_schema.tables WHERE table_name = 'message'";
        Integer found = jdbcTemplate.queryForObject(sql, Integer.class);
        assertThat(found).isEqualTo(1);
    }
}
