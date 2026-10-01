package ch.benedict.m321.batchwriter;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Legt eine Nachricht, die sich nie schreiben laesst, in die Queue chat.dlq.
 *
 * Es gibt diese Klasse, damit eine einzige kaputte Nachricht die Queue nicht verstopft.
 * Wuerde der Dienst sie immer wieder versuchen, kaeme nie etwas anderes mehr durch. In
 * chat.dlq liegt sie dagegen still und kann von Hand angeschaut werden - der Grund steht
 * als Kopfzeile dabei.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    /** Unter diesem Namen steht der Grund in der Nachricht, wenn man sie in der DLQ ansieht. */
    private static final String REASON_HEADER = "x-dead-letter-reason";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public DeadLetterPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Legt den unveraenderten Rumpf in die DLQ. Diesen Weg nehmen Nachrichten, die sich
     * gar nicht lesen liessen: kaputtes JSON oder ein fehlendes Pflichtfeld. Der Rumpf
     * bleibt absichtlich so, wie er ankam, sonst koennte man den Fehler nicht nachsehen.
     */
    public void publishRawBody(byte[] body, String reason) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setHeader(REASON_HEADER, reason);

        Message message = new Message(body, properties);
        send(message, reason);
    }

    /**
     * Legt eine gelesene Nachricht in die DLQ. Diesen Weg nehmen Nachrichten, die lesbar
     * waren, die die Datenbank aber dauerhaft ablehnt, zum Beispiel weil ein Wert zu lang ist.
     */
    public void publishMessage(IncomingMessage message, String reason) {
        byte[] body = toJsonBytes(message);
        publishRawBody(body, reason);
    }

    /**
     * Schickt die Nachricht an den Standard-Exchange. Dessen Besonderheit: der
     * Routing-Key gilt dort als Name der Ziel-Queue. Deshalb steht hier ein leerer
     * Exchange-Name und als Schluessel der Queue-Name.
     */
    private void send(Message message, String reason) {
        rabbitTemplate.send("", RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, message);
        log.warn("Nachricht nach {} gelegt, Grund: {}",
                RabbitConfiguration.DEAD_LETTER_QUEUE_NAME, reason);
    }

    /**
     * Macht aus dem Datensatz wieder JSON. Faellt das aus, legen wir wenigstens den
     * Text der Ausnahme ab: eine Nachricht verschweigen waere schlimmer.
     */
    private byte[] toJsonBytes(IncomingMessage message) {
        try {
            return objectMapper.writeValueAsBytes(message);
        } catch (JsonProcessingException fehler) {
            log.error("Nachricht {} liess sich nicht einmal nach JSON wandeln", message.id(), fehler);
            String ersatz = "{\"id\":\"" + message.id() + "\"}";
            return ersatz.getBytes(StandardCharsets.UTF_8);
        }
    }
}
