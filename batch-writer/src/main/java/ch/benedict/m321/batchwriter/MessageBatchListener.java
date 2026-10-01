package ch.benedict.m321.batchwriter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Hoert auf die Queue chat.persist und schreibt jedes Paket in die Datenbank.
 *
 * Diese Klasse ist die Naht zwischen Broker und Datenbank. Sie bekommt vom Hoer-Behaelter
 * eine fertige LISTE von Nachrichten, nicht einzelne: wie die Pakete entstehen, steht in
 * RabbitConfiguration.
 */
@Component
public class MessageBatchListener {

    private static final Logger log = LoggerFactory.getLogger(MessageBatchListener.class);

    private final MessagePayloadReader payloadReader;
    private final MessageWriter messageWriter;

    public MessageBatchListener(MessagePayloadReader payloadReader, MessageWriter messageWriter) {
        this.payloadReader = payloadReader;
        this.messageWriter = messageWriter;
    }

    /**
     * Nimmt ein Paket aus der Queue, liest jede Nachricht und schreibt alle gemeinsam.
     *
     * Wichtig: diese Methode wird erst verlassen, wenn das Schreiben fertig ist. Erst
     * danach bestaetigt der Behaelter dem Broker das ganze Paket. Wirft die Methode eine
     * Ausnahme, wird nichts bestaetigt und das Paket kommt erneut.
     */
    @RabbitListener(
            queues = RabbitConfiguration.PERSIST_QUEUE_NAME,
            containerFactory = "batchListenerContainerFactory")
    public void receiveBatch(List<Message> batch) {
        log.info("Paket mit {} Nachrichten aus der Queue {} erhalten",
                batch.size(), RabbitConfiguration.PERSIST_QUEUE_NAME);

        List<IncomingMessage> readable = readAll(batch);

        messageWriter.writeBatch(readable);
    }

    /**
     * Liest jede Nachricht des Pakets. Eine dauerhaft unbrauchbare Nachricht darf die
     * anderen nicht mitnehmen, deshalb wird sie hier einzeln abgefangen.
     */
    private List<IncomingMessage> readAll(List<Message> batch) {
        List<IncomingMessage> readable = new ArrayList<>();

        for (Message amqpMessage : batch) {
            try {
                IncomingMessage message = payloadReader.read(amqpMessage.getBody());
                readable.add(message);
            } catch (InvalidMessageException fehler) {
                // Noch unvollstaendig: in Aufgabe 8 des Plans geht eine solche Nachricht
                // nach chat.dlq, damit man sie dort anschauen kann.
                log.warn("Nachricht uebersprungen, weil dauerhaft unbrauchbar: {}",
                        fehler.getMessage());
            }
        }

        return readable;
    }
}
