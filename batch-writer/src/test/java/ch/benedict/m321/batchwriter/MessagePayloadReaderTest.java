package ch.benedict.m321.batchwriter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueft das Lesen des Vertrags aus docs/spec-batch-writer.md, Abschnitt 2.
 *
 * Der wichtigste Test hier ist der erste: die Nachricht wird gelesen, obwohl KEIN
 * Header __TypeId__ dabei ist. Genau so legt Szenario S5 eine Nachricht in die Queue.
 */
@SpringBootTest
class MessagePayloadReaderTest {

    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @Autowired
    private MessagePayloadReader reader;

    @Test
    void readsMessageWithoutTypeHeader() {
        // Genau dieser Rumpf stand im Broker, als wir eine echte Nachricht herausgeholt haben.
        String json = """
                {
                  "id": "ec52e3f3-5411-413e-a617-0f8c81ab9509",
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "Erste Nachricht durch den Broker",
                  "sentAt": "2026-09-11T12:36:52.816460100Z"
                }
                """;

        IncomingMessage message = reader.read(toBytes(json));

        assertThat(message.id()).isEqualTo(UUID.fromString("ec52e3f3-5411-413e-a617-0f8c81ab9509"));
        assertThat(message.roomId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(message.sender()).isEqualTo("lernende1");
        assertThat(message.text()).isEqualTo("Erste Nachricht durch den Broker");
        assertThat(message.sentAt()).isEqualTo(Instant.parse("2026-09-11T12:36:52.816460100Z"));
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {
        // Baut der chat-service spaeter ein Feld dazu, darf der batch-writer nicht
        // abbrechen. Er liest, was er braucht, und laesst den Rest liegen.
        String json = """
                {
                  "id": "ec52e3f3-5411-413e-a617-0f8c81ab9509",
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "Hallo",
                  "sentAt": "2026-09-11T12:36:52Z",
                  "somethingNew": "wird ignoriert"
                }
                """;

        IncomingMessage message = reader.read(toBytes(json));

        assertThat(message.text()).isEqualTo("Hallo");
    }

    @Test
    void rejectsBrokenJson() {
        String json = "{ das ist kein JSON";

        assertThatThrownBy(() -> reader.read(toBytes(json)))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void rejectsMissingText() {
        String json = """
                {
                  "id": "ec52e3f3-5411-413e-a617-0f8c81ab9509",
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "sentAt": "2026-09-11T12:36:52Z"
                }
                """;

        assertThatThrownBy(() -> reader.read(toBytes(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("text");
    }

    @Test
    void rejectsBlankSender() {
        String json = """
                {
                  "id": "ec52e3f3-5411-413e-a617-0f8c81ab9509",
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "   ",
                  "text": "Hallo",
                  "sentAt": "2026-09-11T12:36:52Z"
                }
                """;

        assertThatThrownBy(() -> reader.read(toBytes(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("sender");
    }

    @Test
    void rejectsMissingId() {
        String json = """
                {
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "Hallo",
                  "sentAt": "2026-09-11T12:36:52Z"
                }
                """;

        assertThatThrownBy(() -> reader.read(toBytes(json)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("id");
    }

    /** Macht aus dem Text die Bytes, die in einer AMQP-Nachricht wirklich ankommen. */
    private byte[] toBytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
